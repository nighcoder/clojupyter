(ns clojupyter.kernel.handle-event.comm-msg
  (:require
   [clojupyter.kernel.comm-global-state :as comm-global-state]
   [clojupyter.kernel.comm-atom :as ca]
   [clojupyter.kernel.jup-channels :as jup]
   [clojupyter.log :as log]
   [clojupyter.messages :as msgs]
   [clojupyter.messages-specs :as msp]


   [clojure.spec.alpha :as s]
   [clojure.spec.test.alpha :refer [instrument]]
   [io.simplect.compose :refer [def- C p P]]
   [io.simplect.compose.action :as a :refer [action step side-effect]]))

;;; ------------------------------------------------------------------------------------------------------------------------
;;; MISC INTERNAL
;;; ------------------------------------------------------------------------------------------------------------------------

(def- IOPUB :iopub_port)
(def NO-OP-ACTION
  (action (step `[list] {:op :no-op})))

(defn- jupmsg-spec
  ([port msgtype content]
   (jupmsg-spec port msgtype nil content))
  ([port msgtype metadata content]
   (merge {:op :send-jupmsg, :port port, :msgtype msgtype, :content content}
          (when metadata
            {:metadata metadata}))))

(defmulti ^:private calc*
  (fn [msgtype _ _] msgtype))

(defmethod calc* :default
  [msgtype state ctx]
  (throw (ex-info (str "Unhandled message type: " msgtype)
           {:msgtype msgtype, :state state, :ctx ctx})))

;;; ------------------------------------------------------------------------------------------------------------------------
;;; COMM MESSAGES - HANDLED PER `:method` field
;;; ------------------------------------------------------------------------------------------------------------------------

(defmulti handle-comm-msg
  "The `:method` field of COMM messages determines what needs to happen."
  (fn [method _ _] method))

(defmethod handle-comm-msg :default
  [method S ctx]
  (let [msgstr (str "HANDLE-COMM-MSG - bad method: '" method "'.")
        data {:S S, :ctx ctx}]
    (log/error msgstr data)
    (throw (ex-info msgstr data))))

(defn handle-comm-msg-unknown
  [ctx S comm_id]
  (log/info (str "COMM - unknown comm-id: " comm_id))
  [NO-OP-ACTION S])

(defmethod handle-comm-msg msgs/COMM-MSG-REQUEST-STATE
  [_ S {:keys [req-message jup] :as ctx}]
  (assert (and req-message jup))
  (log/debug "Received COMM:REQUEST-STATE")
  (let [method (msgs/message-comm-method req-message)
        comm-id (msgs/message-comm-id req-message)
        present? (comm-global-state/known-comm-id? S comm-id)]
    (assert method)
    (assert comm-id)
    (assert (= method msgs/COMM-MSG-REQUEST-STATE))
    (if present?
      (let [comm-atom (comm-global-state/comm-atom-get S comm-id)
            content (msgs/comm-msg-content comm-id {:method "update" :state (.sync-state comm-atom)})
            A (action (step [`jup/send!! jup IOPUB req-message msgs/COMM-MSG ca/MESSAGE-METADATA content]
                            (jupmsg-spec IOPUB msgs/COMM-MSG ca/MESSAGE-METADATA content)))]
        [A S])
      (handle-comm-msg-unknown ctx S comm-id))))

(defmethod handle-comm-msg msgs/COMM-MSG-UPDATE
  [_ S {:keys [req-message] :as ctx}]
  (assert req-message)
  (log/debug "Received COMM:UPDATE")
  (let [{{:keys [comm_id] {:keys [method state buffer_paths]} :data} :content} req-message]
    (assert comm_id)
    (assert state)
    (if-let [comm-atom (comm-global-state/comm-atom-get S comm_id)]
      (if (seq buffer_paths)
        (let [_ (log/debug "Received COMM-UPDATE with known comm_id: " comm_id " and state: " state)
              buffers (msgs/message-buffers req-message)
              _ (assert (= (count buffer_paths) (count buffers)))
              [paths _] (msgs/leaf-paths string? keyword buffer_paths)
              repl-map (reduce merge (map hash-map paths buffers))
              _ (log/debug "Got paths: " paths "Got buffer replacement map: " repl-map)
              state (msgs/insert-paths state repl-map)
              A (action (side-effect #(swap! (.-comm-state_ comm-atom) merge state)
                                     {:op :update-agent :comm-id comm_id :new-state state}))]
          [A S])
        (let [A (action (side-effect #(swap! (.-comm-state_ comm-atom) merge state)
                                    {:op :update-agent :comm-id comm_id :new-state state}))]
          (log/debug "Received COMM-UPDATE with empty buffers and known comm_id: " comm_id " and state: " state)
          [A S]))
      (do (log/debug "Received COMM-UPDATE with unknown comm_id: " comm_id " and state: " state)
          (handle-comm-msg-unknown ctx S comm_id)))))

(defmethod handle-comm-msg msgs/COMM-MSG-CUSTOM
  [_ S {:keys [req-message] :as ctx}]
  (assert req-message)
  (let [{{:keys [comm_id] {{event :event :as content} :content :keys [method]} :data} :content} req-message
        buffers (msgs/message-buffers req-message)]
    (assert comm_id)
    (assert (= method msgs/COMM-MSG-CUSTOM))
    (log/debug "Received COMM-CUSTOM -- comm_id: " comm_id " content: " content "buffers: " buffers)
    (if-let [comm-atom (comm-global-state/comm-atom-get S comm_id)]
      (let [k (keyword (str "on-" event))
            state @comm-atom
            callback (get-in state [:callbacks k] (constantly nil))]
        (if (fn? callback)
          (let [A (action (side-effect #(callback comm-atom content buffers) {:op :callback :comm-id comm_id :content content}))]
            [A S])
          ;; If callback attr is not a fn, we assume it's a collection of fns.
          (let [call (fn [] (doseq [f callback]
                              (f comm-atom content buffers)))
                A (action (side-effect call {:op :callback :comm-id comm_id :content content}))]
            [A S])))
      (handle-comm-msg-unknown ctx S comm_id))))

(defmethod calc* msgs/COMM-MSG
  [_ S {:keys [req-message] :as ctx}]
  (assert req-message)
  (let [method (msgs/message-comm-method req-message)]
    (assert method)
    (handle-comm-msg method S ctx)))

;;; ------------------------------------------------------------------------------------------------------------------------
;;; COMM-OPEN, COMM-CLOSE
;;; ------------------------------------------------------------------------------------------------------------------------

;;FIXME: If :target_name does not exist, return COMM_CLOSE as reply.
(defmethod calc* msgs/COMM-OPEN
  [_ S {:keys [req-message jup] :as ctx}]
  (assert (and req-message jup ctx))
  (let [{{:keys [comm_id target_module target_name]
          {:keys [state buffer_paths] :as data} :data :as content} :content}
        ,, req-message]
    (assert S)
    (assert (s/valid? ::msp/target_name target_name))
    (assert (s/valid? ::msp/target_module target_module))
    (assert (map? state))
    (assert (string? comm_id))
    (assert (vector? buffer_paths))
    (if-let [present? (comm-global-state/known-comm-id? S comm_id)]
      (do (log/debug "COMM-OPEN - already present")
          [NO-OP-ACTION S])
      (let [msgtype msgs/COMM-OPEN
            content (msgs/comm-open-content comm_id data {:target_module target_module :target_name target_name})
            comm-atom (ca/create jup req-message target_name comm_id (set (keys state)) state)
            A (action (step nil {:op :comm-add :port IOPUB :msgtype msgtype :content content}))
            S' (comm-global-state/comm-atom-add S comm_id comm-atom)]
          [A S']))))

(defmethod calc* msgs/COMM-CLOSE
  [_ S {:keys [req-message jup] :as ctx}]
  (assert (and req-message jup ctx))
  (let [{{:keys [comm_id data]} :content} req-message]
    (assert S)
    (assert (map? data))
    (assert (string? comm_id))
    (if (comm-global-state/known-comm-id? S comm_id)
      (let [_ (log/debug "Received COMM-CLOSE with known comm_id: " comm_id " and data: " data)
            content (msgs/comm-close-content comm_id {})
            A (action (step nil {:op :comm-remove :port IOPUB :msgtype msgs/COMM-CLOSE :content content}))
            S' (comm-global-state/comm-atom-remove S comm_id)]
        [A S'])
      (do (log/debug "Received COMM-CLOSE with unknown comm_id: " comm_id)
        [NO-OP-ACTION S]))))

;;; ------------------------------------------------------------------------------------------------------------------------
;;; COMM-INFO-REQUEST
;;; ------------------------------------------------------------------------------------------------------------------------

(defmethod calc* msgs/COMM-INFO-REQUEST
  [_ S {:keys [req-message req-port jup] :as ctx}]
  (assert (and req-message req-port jup ctx))
  (let [msgtype msgs/COMM-INFO-REPLY
        content (msgs/comm-info-reply-content (->> (for [comm-id (comm-global-state/known-comm-ids S)]
                                                     [comm-id (.-target (comm-global-state/comm-atom-get S comm-id))])
                                                   (into {})))
        A (action (step [`jup/send!! jup req-port req-message msgtype content]
                        (jupmsg-spec req-port msgtype content)))]
    [A S]))

;; COMM-INFO-REPLY is never received
;; If it were to happen the message would fail in the call to `calc*`

(defn calc
  [& args]
  ;; `spec` & `instrument` seem to struggle with (redefinitions of) multi-methods
  ;; Circumvent using plain fn
  (apply calc* args))

(s/fdef calc
  :args (s/cat :msgtype #{msgs/COMM-CLOSE msgs/COMM-INFO-REPLY msgs/COMM-INFO-REQUEST msgs/COMM-MSG msgs/COMM-OPEN}
               :handler-state comm-global-state/comm-state?
               :ctx (s/and map? (P get :req-message) (P get :jup)))
  :ret (s/and vector?
              (C count (p = 2))
              (C first a/action?)
              (C second comm-global-state/comm-state?)))
(instrument `calc)

;;; ------------------------------------------------------------------------------------------------------------------------
;;; EXTERNAL
;;; ------------------------------------------------------------------------------------------------------------------------

(defn handle-message
  "Handles `req-message` and returns `Action-State` 2-tuple (first element is Action, second is
  State)."
  [state {:keys [req-message] :as ctx}]
  (let [msgtype (msgs/message-msg-type req-message)]
    (calc msgtype state ctx )))
