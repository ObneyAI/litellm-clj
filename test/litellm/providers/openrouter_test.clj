(ns litellm.providers.openrouter-test
  (:require [clojure.test :refer [deftest testing is]]
            [litellm.providers.openrouter :as openrouter]))

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
