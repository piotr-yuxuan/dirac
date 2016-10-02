; nREPL middleware enabling the transparent use of a ClojureScript REPL with nREPL tooling.
; taken from https://github.com/cemerick/piggieback/tree/440b2d03f944f6418844c2fab1e0361387eed543
; original author: Chas Emerick
; Eclipse Public License - v 1.0
;
; this file differs significantly from the original piggieback.clj and was modified to include Dirac-specific functionality
;
(ns dirac.nrepl.piggieback
  (:require [clojure.tools.logging :as log]
            [clojure.string :as string]
            [dirac.logging :as logging]
            [dirac.nrepl.config :as config]
            [dirac.nrepl.state :as state]
            [dirac.nrepl.version :refer [version]]
            [dirac.nrepl.sessions :as sessions]
            [dirac.nrepl.helpers :as helpers]
            [dirac.nrepl.jobs :as jobs]
            [dirac.nrepl.debug :as debug]
            [dirac.nrepl.compilers :as compilers]
            [dirac.nrepl.eval :as eval]
            [dirac.nrepl.messages :as messages]
            [dirac.nrepl.special :as special]
            [dirac.nrepl.joining :as joining]
            [dirac.nrepl.transports.logging :refer [make-nrepl-message-with-logging]]
            [dirac.nrepl.transports.errors-observing :refer [make-nrepl-message-with-observed-errors]]
            [dirac.nrepl.transports.job-observing :refer [make-nrepl-message-with-job-observing-transport]]))

(defn start-new-cljs-compiler-repl-environment! [nrepl-message dirac-nrepl-config repl-env repl-options]
  (log/trace "start-new-cljs-compiler-repl-environment!\n")
  (let [compiler-env nil
        code (or (:repl-init-code dirac-nrepl-config) config/standard-repl-init-code)
        job-id (or (:id nrepl-message) (helpers/generate-uuid))
        ns (:ns nrepl-message)
        effective-repl-options (assoc repl-options
                                 ; the first run through the cljs REPL is effectively part
                                 ; of setup; loading core, (ns cljs.user ...), etc, should
                                 ; not yield a value. But, we do capture the compiler
                                 ; environment now (instead of attempting to create one to
                                 ; begin with, because we can't reliably replicate what
                                 ; cljs.repl/repl* does in terms of options munging
                                 :init (fn []
                                         (compilers/capture-current-compiler-and-select-it!))
                                 :print (fn [& _]
                                          (log/trace "print-fn (no-op)")))                                                    ; silence any responses
        response-fn (partial helpers/send-response! nrepl-message)]
    (eval/eval-in-cljs-repl! code ns repl-env compiler-env effective-repl-options response-fn job-id)))

(defn start-cljs-repl! [nrepl-message dirac-nrepl-config repl-env repl-options]
  (log/trace "start-cljs-repl!\n"
             "dirac-nrepl-config:\n"
             (logging/pprint dirac-nrepl-config)
             "repl-env:\n"
             (logging/pprint repl-env)
             "repl-options:\n"
             (logging/pprint repl-options))
  (debug/log-stack-trace!)
  (try
    (state/set-session-cljs-ns! 'cljs.user)
    (let [preferred-compiler (or (:preferred-compiler dirac-nrepl-config) "dirac/new")]
      (if (= preferred-compiler "dirac/new")
        (start-new-cljs-compiler-repl-environment! nrepl-message dirac-nrepl-config repl-env repl-options)
        (state/set-session-selected-compiler! preferred-compiler)))                                                           ; TODO: validate that preferred compiler exists
    (state/set-session-cljs-repl-env! repl-env)
    (state/set-session-cljs-repl-options! repl-options)
    (state/set-session-original-clj-ns! *ns*)                                                                                 ; interruptible-eval is in charge of emitting the final :ns response in this context
    (set! *ns* (find-ns (state/get-session-cljs-ns)))                                                                         ; TODO: is this really needed? is it for macros?
    (helpers/send-response! nrepl-message (compilers/prepare-announce-ns-msg (state/get-session-cljs-ns)))
    (catch Exception e
      (state/set-session-cljs-repl-env! nil)
      (throw e))))

(defn report-missing-compiler! [nrepl-message selected-compiler available-compilers]
  (let [msg (messages/make-missing-compiler-msg selected-compiler available-compilers)]
    (helpers/send-response! nrepl-message (helpers/make-server-side-output-msg :stderr msg))))

(defn evaluate! [nrepl-message]
  (debug/log-stack-trace!)
  (let [{:keys [session ^String code]} nrepl-message
        cljs-repl-env (state/get-session-cljs-repl-env)]
    ; we append a :cljs/quit to every chunk of code evaluated so we can break out of cljs.repl/repl*'s loop,
    ; so we need to go a gnarly little stringy check here to catch any actual user-supplied exit
    (if-not (.. code trim (endsWith ":cljs/quit"))
      (let [job-id (or (:id nrepl-message) (helpers/generate-uuid))
            ns (:ns nrepl-message)
            mode (:dirac nrepl-message)
            scope-info (:scope-info nrepl-message)
            selected-compiler (state/get-session-selected-compiler)
            cljs-repl-options (state/get-session-cljs-repl-options)
            response-fn (partial helpers/send-response! nrepl-message)]
        (if-let [compiler-env (compilers/get-selected-compiler-env)]
          (eval/eval-in-cljs-repl! code ns cljs-repl-env compiler-env cljs-repl-options response-fn job-id scope-info mode)
          (report-missing-compiler! nrepl-message selected-compiler (compilers/collect-all-available-compiler-ids))))
      (let [original-clj-ns (state/get-session-original-clj-ns)]
        (reset! (:cached-setup cljs-repl-env) :tear-down)                                                                     ; TODO: find a better way
        (cljs.repl/-tear-down cljs-repl-env)
        (sessions/remove-dirac-session-descriptor! session)
        (swap! session assoc #'*ns* original-clj-ns)                                                                          ; TODO: is this really needed?
        (helpers/send-response! nrepl-message {:value         "nil"
                                               :printed-value 1
                                               :ns            (str original-clj-ns)})))))

(defn load-file! [nrepl-message]
  (let [{:keys [file-path]} nrepl-message]
    (evaluate! (assoc nrepl-message :code (format "(load-file %s)" (pr-str file-path))))))

; -- middleware dispatch logic ----------------------------------------------------------------------------------------------

(defn handle-identify-dirac-nrepl-middleware! [_next-handler nrepl-message]
  (helpers/send-response! nrepl-message {:version version}))

(defn handle-eval! [next-handler nrepl-message]
  (let [{:keys [session]} nrepl-message]
    (cond
      (sessions/dirac-session? session) (evaluate! nrepl-message)
      :else (next-handler (make-nrepl-message-with-observed-errors nrepl-message)))))

(defn handle-load-file! [next-handler nrepl-message]
  (let [{:keys [session]} nrepl-message]
    (if (sessions/dirac-session? session)
      (load-file! nrepl-message)
      (next-handler (make-nrepl-message-with-observed-errors nrepl-message)))))

(defn wrap-nrepl-message-if-observed-job [nrepl-message]
  (if-let [observed-job (jobs/get-observed-job nrepl-message)]
    (make-nrepl-message-with-job-observing-transport observed-job nrepl-message)
    nrepl-message))

(defn is-eval-cljs-quit-in-joined-session? [nrepl-message]
  (and (= (:op nrepl-message) "eval")
       (= ":cljs/quit" (string/trim (:code nrepl-message)))
       (sessions/joined-session? (:session nrepl-message))))

(defn issue-dirac-special-command! [nrepl-message command]
  (log/debug "issue-dirac-special-command!" command)
  (special/handle-dirac-special-command! (assoc nrepl-message :code (str "(dirac! " command ")"))))

(defn handle-finish-dirac-job! [nrepl-message]
  (log/debug "handle-finish-dirac-job!")
  (helpers/send-response! nrepl-message (select-keys nrepl-message [:status :err :out])))

(defn handle-known-ops-or-delegate! [nrepl-message next-handler]
  (case (:op nrepl-message)
    "identify-dirac-nrepl-middleware" (handle-identify-dirac-nrepl-middleware! next-handler nrepl-message)
    "finish-dirac-job" (handle-finish-dirac-job! nrepl-message)
    "eval" (handle-eval! next-handler nrepl-message)
    "load-file" (handle-load-file! next-handler nrepl-message)
    (next-handler nrepl-message)))

(defn handle-normal-message! [nrepl-message next-handler]
  (let [{:keys [session] :as nrepl-message} (wrap-nrepl-message-if-observed-job nrepl-message)]
    (cond
      (sessions/joined-session? session) (joining/forward-message-to-joined-session! nrepl-message)
      :else (handle-known-ops-or-delegate! nrepl-message next-handler))))

(defn dirac-nrepl-middleware-handler [next-handler nrepl-message]
  (let [session (:session nrepl-message)]
    (state/ensure-session session
      (let [nrepl-message (make-nrepl-message-with-logging nrepl-message)]
        (log/debug "dirac-nrepl-middleware:" (:op nrepl-message) (sessions/get-session-id session))
        (log/trace "received nrepl message:\n" (debug/pprint-nrepl-message nrepl-message))
        (debug/log-stack-trace!)
        (cond
          (special/dirac-special-command? nrepl-message) (special/handle-dirac-special-command! nrepl-message)
          (is-eval-cljs-quit-in-joined-session? nrepl-message) (issue-dirac-special-command! nrepl-message ":disjoin")
          :else (handle-normal-message! nrepl-message next-handler))))))

; -- nrepl middleware -------------------------------------------------------------------------------------------------------

(defn dirac-nrepl-middleware [next-handler]
  (partial dirac-nrepl-middleware-handler next-handler))
