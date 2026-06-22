(ns jdharms.brevity.core
  (:require [java-time.api :as jt]))

(defn make-context [event system]
  {:event event
   :system system})

(defn execute [system interceptors event]
  (let [ctx (make-context event system)]
    (reduce (fn [ctx i] ((:enter i) ctx)) ctx interceptors)))

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

  (let [interceptors [adds-a-key]]
    (execute system interceptors (event :test-event)))
  
  (event :chat-message))