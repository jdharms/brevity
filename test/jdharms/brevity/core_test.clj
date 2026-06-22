(ns jdharms.brevity.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [jdharms.brevity.core :as sut]))

(deftest execute-test
  (testing "empty interceptor chain returns initial context"
    (let [result (sut/execute {} [] {:type :test-event})]
      (is (= {:type :test-event} (:event result)))))
  
  (testing "single interceptor's enter runs and modifies context"
    (let [add-key {:name "add-key"
                   :enter (fn [ctx] (assoc ctx :added true))}
          result (sut/execute {} [add-key] {:type :test-event})]
      (is (true? (:added result)))))
  
  (testing "interceptors run in order, each seeing prior changes"
    (let [recorder (atom [])
          make-i (fn [n] {:name (str "i" n)
                          :enter (fn [ctx]
                                   (swap! recorder conj n)
                                   ctx)})
          _ (sut/execute {} [(make-i 1) (make-i 2) (make-i 3)] {:type :test-event})]
      (is (= [1 2 3] @recorder)))))
