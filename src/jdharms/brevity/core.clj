(ns jdharms.brevity.core
  (:require [java-time.api :as jt]))

(defn make-context [event system]
  {:event event
   :system system})

(defn execute [system chain event]
  (let [initial-ctx (make-context event system)
        initial-on-leave []
        after-enters (reduce (fn [{:keys [ctx on-leave]} i] 
                               (let [new-ctx ((:enter i) ctx)
                                     new-on-leave (if (:leave i) (conj on-leave i) on-leave)]
                                 {:ctx new-ctx :on-leave new-on-leave})) {:ctx initial-ctx :on-leave initial-on-leave} chain)
        after-leaves (reduce (fn [ctx i] ((:leave i) ctx)) (:ctx after-enters) (reverse (:on-leave after-enters)))]
    
    after-leaves))


(comment
  (defn event [type]
    {:type type
     :timestamp (jt/instant)})
  
  (defn interceptor [name enter]
    {:name name
     :enter enter
     :leave nil})
  
  (def system {:state (atom {})})

  (def adds-a-key (interceptor "adds-a-key" (fn [ctx] (assoc ctx :my-new-key "here's a value"))))

  (let [chain [adds-a-key]]
    (execute system chain (event :test-event)))
  
  (event :chat-message))