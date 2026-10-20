(ns logseq-hooks.anthropic-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [logseq-hooks.anthropic :as anthropic]))

(defn- body [stop-reason & blocks]
  (json/generate-string {:stop_reason stop-reason
                         :content (vec blocks)
                         :usage {:output_tokens 400}}))

(def thinking {:type "thinking" :thinking "" :signature "sig"})

(deftest text-is-returned-from-a-complete-reply
  (is (= {:text "A subject"}
         (anthropic/response->result
          200 (body "end_turn" thinking {:type "text" :text "A subject\n"})))))

(deftest a-reply-cut-off-at-max-tokens-is-an-error
  (testing "even when there is text, since that text is incomplete"
    (let [{:keys [text error]} (anthropic/response->result
                                200 (body "max_tokens" thinking
                                          {:type "text" :text "Journal: notes on t"}))]
      (is (nil? text))
      (is (str/includes? error "max_tokens")))))

(deftest a-reply-of-only-thinking-is-an-error
  (is (= {:error "response contained no text"}
         (anthropic/response->result 200 (body "end_turn" thinking)))))

(deftest a-non-200-status-is-an-error
  (is (str/starts-with? (:error (anthropic/response->result 404 "not found"))
                        "HTTP 404")))
