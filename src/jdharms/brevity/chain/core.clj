; Copyright 2023-2026 Nubank NA
; Copyright 2013 Relevance, Inc.
; Copyright 2014-2022 Cognitect, Inc.
; Copyright 2026 Daniel Harms

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

; NOTE: This namespace is heavily inspired by the Pedestal library.
; I implemented it using the file at:
; https://github.com/pedestal/pedestal/blob/master/interceptor/src/io/pedestal/interceptor/chain.clj
; as a guide.  I would consider this a derivative work and I'm including the original
; license text above. This *file* retains it's EPL-1.0 license.

(ns jdharms.brevity.chain.core
  (:require [java-time.api :as jt])
  (:import java.util.concurrent.atomic.AtomicLong
           (clojure.lang PersistentQueue)))

(defn make-context [event system]
  {:event event
   :system system})

(defn- name-for
  [interceptor]
  (or (get interceptor :name)
      "anonymous interceptor"))

(defn- into-queue [queue values]
  (into (or queue PersistentQueue/EMPTY) values))

(defn enqueue
  "Adds chain to the end of the context's queue, creating the queue if necessary."
  [ctx chain]
  (update ctx ::queue into-queue chain))

(def ^:private ^AtomicLong execution-id (AtomicLong.))

(defn- begin
  [ctx]
  (if (contains? ctx ::execution-id)
    ctx
    (assoc ctx ::execution-id (.incrementAndGet execution-id))))

(defn- throwable->ex-info
  [^Throwable t execution-id interceptor-name stage]
  (let [throwable-str (pr-str (type t))
        message (.getMessage t)
        message' (when message
                   (str " - " message))]
    (ex-info (str throwable-str " in Interceptor " interceptor-name message')
             (merge {:execution-id execution-id
                     :stage stage
                     :interceptor interceptor-name
                     :exception-type (keyword throwable-str)
                     :exception t}
                    (ex-data t))
             t)))

(defn with-error
  [ctx ^Throwable t]
  (assoc ctx ::error t))

(defn clear-error
  [ctx]
  (dissoc ctx ::error))

(defn terminate
  "Removes all remaining interceptors from the execution queue."
  [ctx]
  (dissoc ctx ::queue))

(defn- begin-error
  "Clears the execution queue and attaches error information to ctx"
  [ctx stage interceptor throwable]
  (let [{:keys [execution-id]} ctx
        interceptor-name (name-for interceptor)]
    ; log here later?
    (-> ctx
        (dissoc ::queue)
        (with-error (throwable->ex-info throwable
                                        execution-id
                                        interceptor-name
                                        stage)))))

(defn- go-async
  [_interceptor _stage _ctx _cxt-chan] nil)

(defn- async-marker? [_x] false) ;; literally always false for now

(defn- try-stage
  "Extracts a callback from an interceptor and calls it if non-nil."
  [ctx interceptor stage]
  (if-let [callback (get interceptor stage)]
    (try
      (with-bindings (or (:bindings ctx) {})
        (callback ctx)) ; here's where we'd check terminators if/when implemented
      (catch Throwable t
        (begin-error ctx stage interceptor t)))
    ctx))

(defn- try-error
  "Invoke the interceptor's :error callback"
  [ctx interceptor error]
  (if-let [callback (get interceptor :error)]
    (let [context-in (dissoc ctx ::error)]
      (try
        (with-bindings (or (:bindings ctx) {})
          (callback context-in error))
        (catch Throwable t
          ;; The error handler can rethrow
          (if (identical? t error)
            ctx
            (let [execution-id (::execution-id ctx)]
              (-> ctx
                  (with-error (throwable->ex-info t execution-id (name-for interceptor) :error))
                  (update ::suppressed conj error)))))))
    ctx))

(defn- execute-enter
  [ctx]
  (loop [ctx ctx]
    (let [queue (::queue ctx)
          interceptor (peek queue)]
      (if (nil? interceptor)
        (dissoc ctx ::queue)
        (let [ctx' (assoc ctx
                          ::queue (pop queue)
                          ::stack (conj (::stack ctx) interceptor))
              out (try-stage ctx' interceptor :enter)]
          (if (async-marker? out)
            (go-async interceptor :enter ctx out)
            (recur out)))))))

(defn- prepare-for-leave
  [ctx]
  (if (contains? ctx ::stack)
    (let [stack (::stack ctx)]
      (-> ctx
          (assoc ::leave-queue (reverse stack))
          (dissoc ::stack)))
    ;; ctx has already been prepared and has ::leave-queue
    ctx))

(defn- execute-leave
  [ctx]
  (loop [ctx ctx]
    (let [queue (::leave-queue ctx)
          interceptor (peek queue)]
      (if (nil? interceptor)
        (dissoc ctx ::leave-queue)
        (let [ctx' (assoc ctx ::leave-queue (pop queue))
              error (::error ctx)
              out (if error
                    (try-error ctx' interceptor error)
                    (try-stage ctx' interceptor :leave))]
          (if (async-marker? out)
            (go-async interceptor :leave ctx out)
            (recur out)))))))

(defn execute
  "Executes a queue of interceptors attached to a context"
  ([ctx]
   (-> ctx
       (assoc ::stack PersistentQueue/EMPTY)
       begin
       execute-enter
       prepare-for-leave
       execute-leave))
  ([ctx chain]
   (execute (enqueue ctx chain))))


(comment
  (defn event [type]
    {:type type
     :timestamp (jt/instant)})

  (defn interceptor
    ([name enter]
     {:name name
      :enter enter
      :leave nil})
    ([name enter leave]
     {:name name
      :enter enter
      :leave leave}))

  (def system {:state (atom {})})

  (def adds-a-key (interceptor "adds-a-key"
                               (fn [ctx]
                                 (throw (Exception. "Failure"))
                                 (assoc ctx :my-new-key "here's a value"))
                               (fn [ctx] (assoc ctx :another-key "added this on leave"))))

  (let [chain [adds-a-key]]
    (execute (make-context (event :test-event) system) chain))

  (event :chat-message))