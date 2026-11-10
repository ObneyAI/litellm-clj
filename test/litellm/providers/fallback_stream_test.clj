(ns litellm.providers.fallback-stream-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as a]
            [litellm.providers.openrouter :as router])
  (:import [com.sun.net.httpserver HttpServer HttpHandler]
           [java.net InetSocketAddress]))
(deftest startup-failure-retains-safe-status
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/chat/completions"
      (reify HttpHandler (handle [_ exchange]
        (let [b (.getBytes "unavailable" "UTF-8")]
          (.sendResponseHeaders exchange 503 (alength b))
          (with-open [out (.getResponseBody exchange)] (.write out b))))))
    (.start server)
    (try
      (let [ch (router/make-streaming-request-impl :openrouter {:model "fixture" :messages []} nil
                 {:api-key "SECRET-FIXTURE-NOT-A-KEY" :api-base (str "http://127.0.0.1:" (.getPort (.getAddress server)))})
            [result port] (a/alts!! [ch (a/timeout 5000)])]
        (is (= ch port))
        (is (= :error (:type result)))
        (is (= 503 (:http-status result)))
        (is (= :litellm/server-error (:error-type result)))
        (is (not (.contains (pr-str result) "SECRET-FIXTURE-NOT-A-KEY")))
        (is (nil? (a/<!! ch))))
      (finally (.stop server 0)))))
