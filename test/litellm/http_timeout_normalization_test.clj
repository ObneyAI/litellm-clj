(ns litellm.http-timeout-normalization-test
  (:require [clojure.test :refer [deftest is testing]]
            [litellm.errors :as errors])
  (:import [java.net SocketTimeoutException]
           [java.net.http HttpTimeoutException]
           [java.util.concurrent ExecutionException]))

(defn thrown-by [effect]
  (try (errors/wrap-http-errors "controlled-provider" effect)
       nil
       (catch Throwable error error)))

(deftest java-http-timeout-retains-meaning-through-async-wrapper
  (doseq [[label original] [[:direct (HttpTimeoutException. "controlled timeout")]
                          [:asynchronous (ExecutionException. (HttpTimeoutException. "controlled timeout"))]]]
    (testing (name label)
      (let [normalized (thrown-by #(throw original))
            data (ex-data normalized)]
        (is (= :litellm/timeout (:type data)))
        (is (= "controlled-provider" (:provider data)))
        (is (true? (:recoverable? data)))
        (is (not-any? #(contains? data %) [:http-status :model :resolved-model :usage]))))))

(deftest timeout-normalization-preserves-other-http-boundaries
  (testing "existing socket timeout"
    (let [data (ex-data (thrown-by #(throw (SocketTimeoutException. "controlled socket timeout"))))]
      (is (= :litellm/timeout (:type data)))
      (is (true? (:recoverable? data)))))
  (testing "typed errors retain exact identity through a future"
    (let [original (errors/authentication-error "controlled-provider" "controlled denial")]
      (is (identical? original (thrown-by #(throw original))))
      (is (identical? original (thrown-by #(throw (ExecutionException. original)))))))
  (testing "real HTTP response classification remains authoritative"
    (doseq [[status type recoverable?] [[503 :litellm/server-error true]
                                      [401 :litellm/authentication-error false]]]
      (let [original (ex-info "controlled response"
                             {:status status :headers {"x-request-id" "controlled-id"}
                              :body "{\"error\":{\"message\":\"controlled response\"}}"})
            data (ex-data (thrown-by #(throw (ExecutionException. original))))]
        (is (= type (:type data)))
        (is (= status (:http-status data)))
        (is (= recoverable? (:recoverable? data)))
        (is (not-any? #(contains? data %) [:model :resolved-model :usage])))))
  (testing "an unknown asynchronous failure remains a provider error"
    (let [data (ex-data (thrown-by #(throw (ExecutionException. (IllegalStateException. "controlled unknown")))))]
      (is (= :litellm/provider-error (:type data)))
      (is (false? (:recoverable? data)))
      (is (not (contains? data :http-status)))))
  (testing "a successful callback is unchanged"
    (let [result (Object.)]
      (is (identical? result (errors/wrap-http-errors "controlled-provider" (constantly result)))))))
