(ns litellm.providers.openrouter-request-options-test
  (:require [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [hato.client :as http]
            [litellm.core :as llm]
            [litellm.errors :as errors]
            [litellm.providers.core :as providers])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(def optional-settings
  {:max-tokens :max_tokens
   :temperature :temperature
   :top-p :top_p
   :frequency-penalty :frequency_penalty
   :presence-penalty :presence_penalty
   :stop :stop})

(def request {:messages [{:role :user :content "Return the synthetic result"}]})
(def response-body
  {:id "options-fixture" :object "chat.completion" :created 1 :model "fixture"
   :choices [{:index 0 :finish_reason "stop"
              :message {:role "assistant" :content "{}"}}]
   :usage {:prompt_tokens 2 :completion_tokens 3 :total_tokens 5}})

(deftest omitted-generation-options-are-absent-on-the-http-wire
  (let [bodies (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/chat/completions"
      (reify HttpHandler
        (handle [_ exchange]
          (let [body (with-open [input (.getRequestBody exchange)]
                       (json/decode (slurp input) true))
                _ (swap! bodies conj body)
                streaming? (:stream body)
                result (if streaming?
                         (str "data: " (json/encode
                                         {:id "options-fixture" :object "chat.completion.chunk"
                                          :created 1 :model "fixture"
                                          :choices [{:index 0 :delta {:role "assistant" :content "{}"}
                                                     :finish_reason "stop"}]})
                              "\n\ndata: [DONE]\n\n")
                         (json/encode response-body))
                bytes (.getBytes result "UTF-8")]
            (.set (.getResponseHeaders exchange) "Content-Type"
                  (if streaming? "text/event-stream" "application/json"))
            (.sendResponseHeaders exchange 200 (alength bytes))
            (with-open [out (.getResponseBody exchange)] (.write out bytes))))))
    (.start server)
    (try
      (let [config {:api-key "SYNTHETIC-NOT-A-KEY" :timeout 5000
                    :api-base (str "http://127.0.0.1:" (.getPort (.getAddress server)))}
            blocking (llm/completion :openrouter "fixture" request config)
            channel (llm/completion :openrouter "fixture" (assoc request :stream true) config)
            [chunk port] (async/alts!! [channel (async/timeout 5000)])
            [end end-port] (async/alts!! [channel (async/timeout 5000)])]
        (is (= "{}" (get-in blocking [:choices 0 :message :content])))
        (is (= channel port))
        (is (= "{}" (get-in chunk [:choices 0 :delta :content])))
        (is (= channel end-port))
        (is (nil? end))
        (is (= 2 (count @bodies)))
        (is (= [false true] (mapv :stream @bodies)))
        (doseq [body @bodies]
          (is (= {} (select-keys body (vals optional-settings)))))
        (is (= {:include_usage true} (:stream_options (second @bodies)))))
      (finally (.stop server 0)))))

(deftest explicit-values-schema-nulls-and-provider-controls-survive-serialization
  (let [schema {:type "object" :properties {:result-name {:type ["string" "null"] :default nil}}
                :required ["result-name"] :additionalProperties false}
        format {:type "json_schema" :json_schema {:name "result" :strict true :schema schema}}
        provider {:only ["fixture-provider"] :allow_fallbacks false :data_collection "deny"
                  :require_parameters true}
        bodies (atom [])]
    (doseq [stop [[] ""]]
      (with-redefs [http/post (fn [_ options]
                               (swap! bodies conj (json/decode (:body options) true))
                               (doto (promise) (deliver {:status 200 :body response-body})))]
        (llm/completion :openrouter "fixture"
          (merge request {:max-tokens 32768 :temperature 0.0 :top-p 0.0
                          :frequency-penalty 0.0 :presence-penalty 0.0 :stop stop :stream false
                          :thinking {:type :disabled} :response-format format
                          :extra-body {:provider provider :custom-null nil :custom-empty []
                                       :custom-false false}})
          {:api-key "SYNTHETIC-NOT-A-KEY"})))
    (is (= 2 (count @bodies)))
    (is (= [[] ""] (mapv :stop @bodies)))
    (doseq [body @bodies]
      (is (= {:max_tokens 32768 :temperature 0.0 :top_p 0.0
              :frequency_penalty 0.0 :presence_penalty 0.0}
             (select-keys body [:max_tokens :temperature :top_p :frequency_penalty :presence_penalty])))
      (is (false? (:stream body)))
      (is (= {:enabled false} (:reasoning body)))
      (is (= format (:response_format body)))
      (is (= provider (:provider body)))
      (is (= {:custom-null nil :custom-empty [] :custom-false false}
             (select-keys body [:custom-null :custom-empty :custom-false]))))
    (testing "Explicit raw provider values retain existing precedence, including null"
      (let [body (providers/transform-request :openrouter
                   (assoc request :model "fixture" :temperature 0.0 :extra-body {:temperature nil}) {})]
        (is (contains? body :temperature))
        (is (nil? (:temperature body)))))))

(deftest adapter-does-not-synthesize-null-options-or-relax-public-validation
  (let [nil-options (zipmap (keys optional-settings) (repeat nil))
        body (providers/transform-request :openrouter (merge request {:model "fixture"} nil-options) {})]
    (is (= {} (select-keys body (vals optional-settings))))
    (doseq [option (keys optional-settings)]
      (let [calls (atom 0)
            error (with-redefs [http/post (fn [& _] (swap! calls inc))]
                    (try (llm/completion :openrouter "fixture" (assoc request option nil)
                           {:api-key "SYNTHETIC-NOT-A-KEY"})
                         nil
                         (catch clojure.lang.ExceptionInfo e e)))]
        (is (errors/error-type? error :litellm/invalid-request))
        (is (zero? @calls))))))
