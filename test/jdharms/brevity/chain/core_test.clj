(ns jdharms.brevity.chain.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [jdharms.brevity.chain.core :as sut]))

(defn test-ctx [] 
  (sut/make-context {:type :test-event} {}))

  (deftest execute-test
    (testing "empty interceptor chain returns initial context"
      (let [result (sut/execute (test-ctx) [])]
        (is (= {:type :test-event} (:event result)))))
    
    (testing "single interceptor's enter runs and modifies context"
      (let [add-key {:name "add-key"
                     :enter (fn [ctx] (assoc ctx :added true))}
            result (sut/execute (test-ctx) [add-key])]
        (is (true? (:added result)))))
    
    (testing "interceptors run in order, each seeing prior changes"
      (let [recorder (atom [])
            make-i (fn [n] {:name (str "i" n)
                            :enter (fn [ctx]
                                     (swap! recorder conj n)
                                     ctx)})
            _ (sut/execute (test-ctx) [(make-i 1) (make-i 2) (make-i 3)])]
        (is (= [1 2 3] @recorder))))
    
    (testing "single interceptor's leave runs after enter"
      (let [recorder (atom [])
            record (fn [phase] (fn [ctx] (swap! recorder conj phase) ctx))
            i {:name "i"
               :enter (record :enter)
               :leave (record :leave)}
            _ (sut/execute (test-ctx) [i])]
        (is (= [:enter :leave] @recorder))))
    
    (testing "leave functions run in reverse order"
      (let [recorder (atom [])
            record (fn [phase] (fn [ctx] (swap! recorder conj phase) ctx))
            a {:name "a"
               :enter (record :enter-a)
               :leave (record :leave-a)}
            b {:name "b"
               :enter (record :enter-b)
               :leave (record :leave-b)}
            _ (sut/execute (test-ctx) [a b])]
        (is (= [:enter-a :enter-b :leave-b :leave-a] @recorder))))
    
    (testing "execute handles interceptors without :leave properly"
      (let [recorder (atom [])
            record (fn [phase] (fn [ctx] (swap! recorder conj phase) ctx))
            a {:name "a"
               :enter (record :enter-a)
               :leave (record :leave-a)}
            b {:name "b"
               :enter (record :enter-b)
               :leave nil}
            _ (sut/execute (test-ctx) [a b])]
        (is (= [:enter-a :enter-b :leave-a] @recorder)))))
