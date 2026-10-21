(ns logseq-hooks.anthropic
  "A single-shot client for the Messages API.

  The contract is that this never throws and never blocks indefinitely: a hook
  that fails because the network is unavailable is worse than a hook that
  writes a dull commit message, so every failure comes back as data."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def endpoint "https://api.anthropic.com/v1/messages")
(def api-version "2023-06-01")

(defn- extract-text [body]
  (->> (:content body)
       (filter #(= "text" (:type %)))
       (map :text)
       (str/join "\n")
       str/trim
       not-empty))

(defn- truncate [s n]
  (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn response->result
  "Interprets a Messages API response as {:text ...} or {:error ...}.

  A reply that stopped at `max_tokens` is an error even when it carries text:
  that text was cut off mid-sentence and nothing downstream can tell it apart
  from a reply that was meant to be short."
  [status body]
  (if (= 200 status)
    (let [parsed (json/parse-string body true)]
      (if (= "max_tokens" (:stop_reason parsed))
        {:error (format "response truncated at max_tokens (%s output tokens)"
                        (get-in parsed [:usage :output_tokens]))}
        (if-let [text (extract-text parsed)]
          {:text text}
          {:error "response contained no text"})))
    {:error (format "HTTP %s: %s" status (truncate (str body) 200))}))

(defn- request [{:keys [api-key model max-tokens system prompt thinking effort timeout-ms]}]
  (let [{:keys [status body]}
        (http/post endpoint
                   {:headers {"x-api-key" api-key
                              "anthropic-version" api-version
                              "content-type" "application/json"}
                    :body (json/generate-string
                           (cond-> {:model model
                                    :max_tokens max-tokens
                                    :messages [{:role "user" :content prompt}]}
                             system (assoc :system system)
                             thinking (assoc :thinking thinking)
                             effort (assoc :output_config {:effort effort})))
                    :timeout timeout-ms
                    :throw false})]
    (response->result status body)))

(defn message
  "Sends `prompt` and returns {:text ...} or {:error ...}.

  `timeout-ms` is enforced twice: once by the HTTP client and once by the
  surrounding deref, so a client that ignores its own deadline still cannot
  stall a commit."
  [{:keys [timeout-ms] :as opts}]
  (let [call (future (try (request opts)
                          (catch Exception e {:error (ex-message e)})))]
    (or (deref call timeout-ms nil)
        (do (future-cancel call)
            {:error (format "timed out after %dms" timeout-ms)}))))
