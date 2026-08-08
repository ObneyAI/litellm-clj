(ns litellm.providers.openrouter-test
  (:require [clojure.test :refer [deftest testing is]]
            [hato.client :as http]
            [litellm.providers.openrouter :as openrouter]))

(def ^:private response-fixture
  {:body {:id "gen-1"
          :object "chat.completion"
          :created 1
          :model "google/gemini-3.5-flash"
          :choices [{:index 0
                     :message {:role "assistant"
                               :content "hi"
                               :tool_calls [{:id "c1"
                                             :type "function"
                                             :function {:name "f"
                                                        :arguments "{}"}}]}
                     :finish_reason "tool_calls"}]
          :usage {:prompt_tokens 1 :completion_tokens 2 :total_tokens 3}}})

(deftest test-transform-response
  (testing "Transform OpenRouter response to standard format"
    (let [transformed (openrouter/transform-response-impl :openrouter response-fixture)]
      (is (= "gen-1" (:id transformed)))
      (is (= :tool_calls (get-in transformed [:choices 0 :finish-reason])))
      (is (= "hi" (get-in transformed [:choices 0 :message :content]))))))

(deftest test-response-collections-are-vectors
  (testing "Indexed access works on :choices and :tool-calls"
    (let [transformed (openrouter/transform-response-impl :openrouter response-fixture)]
      (is (vector? (:choices transformed)))
      (is (vector? (get-in transformed [:choices 0 :message :tool-calls])))
      (is (= "f" (get-in transformed
                         [:choices 0 :message :tool-calls 0 :function :name]))))))

(deftest test-transform-tool-calls-vector
  (testing "transform-tool-calls returns a vector"
    (is (vector? (openrouter/transform-tool-calls
                  [{:id "c1" :type "function"
                    :function {:name "f" :arguments "{}"}}])))))

(deftest test-make-request-times-out-and-cancels-stalled-response
  (testing "A response future that never completes fails within the configured timeout"
    (let [response (future (Thread/sleep 60000))
          started-at (System/currentTimeMillis)
          error (with-redefs [http/post (fn [_url _options] response)]
                  (try
                    (openrouter/make-request-impl
                     :openrouter
                     {:model "anthropic/claude-sonnet-5" :messages []}
                     nil
                     nil
                     {:api-key "test" :timeout 10})
                    nil
                    (catch clojure.lang.ExceptionInfo e
                      e)))]
      (is (= :litellm/timeout (:type (ex-data error))))
      (is (= "openrouter" (:provider (ex-data error))))
      (is (= 10 (get-in (ex-data error) [:context :timeout-ms])))
      (is (future-cancelled? response))
      (is (< (- (System/currentTimeMillis) started-at) 1000)))))

;; ============================================================================
;; Streaming Usage Tests
;; ============================================================================

(deftest test-transform-request-stream-options
  (testing "Streaming request injects stream_options with include_usage"
    (let [request {:model "openai/gpt-4"
                  :messages [{:role :user :content "Hello"}]
                  :stream true}
          transformed (openrouter/transform-request-impl :openrouter request {})]
      (is (true? (:stream transformed)))
      (is (= {:include_usage true} (:stream_options transformed)))))

  (testing "Streaming request merges caller-provided stream_options"
    (let [request {:model "openai/gpt-4"
                  :messages [{:role :user :content "Hello"}]
                  :stream true
                  :stream_options {:include_obfuscation true}}
          transformed (openrouter/transform-request-impl :openrouter request {})]
      (is (= {:include_usage true
              :include_obfuscation true}
             (:stream_options transformed)))))

  (testing "Caller-provided stream_options values are not clobbered"
    (let [request {:model "openai/gpt-4"
                  :messages [{:role :user :content "Hello"}]
                  :stream true
                  :stream_options {:include_usage false}}
          transformed (openrouter/transform-request-impl :openrouter request {})]
      (is (= {:include_usage false} (:stream_options transformed)))))

  (testing "Non-streaming request has no stream_options"
    (let [request {:model "openai/gpt-4"
                  :messages [{:role :user :content "Hello"}]}
          transformed (openrouter/transform-request-impl :openrouter request {})]
      (is (false? (:stream transformed)))
      (is (not (contains? transformed :stream_options))))))

(deftest test-transform-streaming-chunk-usage
  (testing "Chunk with usage preserves usage in standard shape"
    (let [chunk {:id "gen-123"
                :object "chat.completion.chunk"
                :created 1700000000
                :model "openai/gpt-4"
                :choices []
                :usage {:prompt_tokens 10
                        :completion_tokens 20
                        :total_tokens 30}}
          transformed (openrouter/transform-streaming-chunk-impl :openrouter chunk)]
      (is (= {:prompt-tokens 10
              :completion-tokens 20
              :total-tokens 30}
             (:usage transformed)))))

  (testing "Chunk without usage has no usage key"
    (let [chunk {:id "gen-123"
                :object "chat.completion.chunk"
                :created 1700000000
                :model "openai/gpt-4"
                :choices [{:index 0
                          :delta {:role "assistant" :content "Hello"}}]}
          transformed (openrouter/transform-streaming-chunk-impl :openrouter chunk)]
      (is (not (contains? transformed :usage)))
      (is (= "Hello" (get-in transformed [:choices 0 :delta :content]))))))
