(ns litellm.providers.openrouter-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [hato.client :as http]
            [litellm.core :as llm]
            [litellm.errors :as errors]
            [litellm.schemas :as schemas]))

(def response {:status 200 :body {:id "contract-1" :object "chat.completion"
  :created 1 :model "fixture" :choices [{:index 0 :finish_reason "stop"
  :message {:role "assistant" :content "{}"}}]
  :usage {:prompt_tokens 2 :completion_tokens 3 :total_tokens 5}}})

(defn complete [request response-value]
  (let [bodies (atom [])]
    (with-redefs [http/post (fn [_ options]
                             (swap! bodies conj (json/decode (:body options) true))
                             (doto (promise) (deliver response-value)))]
      {:result (llm/completion :openrouter "fixture"
                 (merge {:messages [{:role :user :content "Return the result"}]} request)
                 {:api-key "test-only"})
       :bodies @bodies})))

(deftest explicit-function-strict-reaches-wire
  (doseq [strict [true false]]
    (let [{:keys [bodies]} (complete
                           {:tools [{:type "function" :function
                                     {:name "result" :strict strict :parameters
                                      {:type "object" :properties {:result-name {:type "string"}}
                                       :required ["result-name"] :additionalProperties false}}}]}
                           response)]
      (is (= 1 (count bodies)))
      (is (= strict (get-in bodies [0 :tools 0 :function :strict])))
      (is (= {:type "string"} (get-in bodies [0 :tools 0 :function :parameters :properties :result-name]))))))

(def output-schema {:type "object" :properties {:result-name {:type "string"}}
                    :required ["result-name"] :additionalProperties false})

(deftest structured-format-reaches-wire-with-literal-schema
  (doseq [format [{:type "json_schema" :json_schema {:name "result" :strict true :schema output-schema}}
                  {:type :json-schema :json-schema {:name "result" :strict true :schema output-schema}}]]
    (let [{:keys [bodies]} (complete {:response-format format} response)]
      (is (= {:type "json_schema" :json_schema {:name "result" :strict true :schema output-schema}}
             (:response_format (first bodies))))))
  (is (not (contains? (first (:bodies (complete {} response))) :response_format))))

(deftest conflicting-raw-format-is-rejected-before-http
  (let [calls (atom 0)
        error (with-redefs [http/post (fn [& _] (swap! calls inc) (doto (promise) (deliver response)))]
                (try (llm/completion :openrouter "fixture"
                       {:messages [{:role :user :content "Result"}]
                        :response-format {:type "json_schema" :json_schema {:name "result" :schema output-schema}}
                        :extra-body {:response_format {:type "text"}}}
                       {:api-key "test-only"})
                     nil
                     (catch clojure.lang.ExceptionInfo e e)))]
    (is (errors/error-type? error :litellm/invalid-request))
    (is (zero? @calls)))
  (is (= {:type "json_object"}
         (:response_format (first (:bodies (complete {:extra-body {:response_format {:type "json_object"}}} response)))))))

(deftest returned-observations-survive-public-completion
  (doseq [n [39 41]]
    (let [body (json/decode (slurp (str "test/fixtures/openrouter/response-" n ".json")) true)
          original (get-in body [:choices 0 :message])
          result (:result (complete {} {:status 200 :body body}))
          message (get-in result [:choices 0 :message])]
      (is (= (:content original) (:content message)))
      (is (= (:reasoning original) (:reasoning-content message)))
      (is (= (contains? original :reasoning) (contains? message :reasoning-content)))
      (is (= (:reasoning_details original) (:reasoning-details message)))
      (is (= (mapv :function (:tool_calls original)) (mapv :function (:tool-calls message))))
      (is (= (keyword (get-in body [:choices 0 :finish_reason])) (get-in result [:choices 0 :finish-reason])))
      (doseq [[wire normalized] [[:prompt_tokens_details :prompt-tokens-details]
                                [:completion_tokens_details :completion-tokens-details]
                                [:cost_details :cost-details] [:cost :cost] [:is_byok :is-byok]]]
        (is (= (get-in body [:usage wire]) (get-in result [:usage normalized]))))))
  (doseq [observations [{} {:reasoning nil :reasoning_details nil}]]
    (let [body (update-in (:body response) [:choices 0 :message] merge observations)
          message (get-in (:result (complete {} {:status 200 :body body})) [:choices 0 :message])]
      (is (= (contains? observations :reasoning) (contains? message :reasoning-content)))
      (is (= (contains? observations :reasoning_details) (contains? message :reasoning-details)))))
  (let [usage (:usage (:result (complete {} response)))]
    (is (not (contains? usage :cost)))
    (is (not (contains? usage :completion-tokens-details)))))

(deftest response-schema-retains-observations-and-explicit-null
  (doseq [observations [{:reasoning nil :reasoning_details nil}
                        {:reasoning "Provider text" :reasoning_details [{:type "reasoning.encrypted" :data "opaque" :index 0}]}]]
    (let [body (-> (:body response)
                   (update-in [:choices 0 :message] merge observations)
                   (update :usage merge {:cost nil :is_byok false :cost_details nil
                                         :completion_tokens_details {:reasoning_tokens 0}
                                         :prompt_tokens_details {:cached_tokens 0}}))
          result (:result (complete {} {:status 200 :body body}))]
      (is (schemas/valid-response? result))
      (when (schemas/valid-response? result)
        (let [encoded (schemas/transform-response result)]
          (is (= (:usage result) (:usage encoded)))
          (is (= (select-keys (get-in result [:choices 0 :message]) [:reasoning-content :reasoning-details])
                 (select-keys (get-in encoded [:choices 0 :message]) [:reasoning-content :reasoning-details]))))))))

(deftest refusal-presence-survives-completion-and-response-schema
  (doseq [observation [{} {:refusal nil} {:refusal "Cannot fulfill this request."}]]
    (let [body (update-in (:body response) [:choices 0 :message] merge observation)
          result (:result (complete {} {:status 200 :body body}))
          message (get-in result [:choices 0 :message])
          encoded-message (get-in (schemas/transform-response result) [:choices 0 :message])]
      (is (= observation (select-keys message [:refusal])))
      (is (= observation (select-keys encoded-message [:refusal]))))))
