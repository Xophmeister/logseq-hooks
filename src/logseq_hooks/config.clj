(ns logseq-hooks.config
  "Configuration for the Logseq hooks, read from `.git/logseq-hooks.edn`.

  The file lives inside the git directory rather than the working tree for two
  reasons: it holds an API key and Logseq would otherwise sync it. The
  consequence is that a fresh clone has no configuration, so `require-key`
  fails loudly rather than letting a nil propagate."
  (:require [clojure.edn :as edn]
            [babashka.fs :as fs]
            [logseq-hooks.git :as git]))

(def config-filename "logseq-hooks.edn")

(def defaults
  {;; Message Logseq uses for its own commits. Only commits carrying exactly
   ;; this message are eligible for rewriting or deferral; everything else is
   ;; assumed to be a deliberate commit and left completely alone.
   :auto-commit-message "Auto saved by Logseq"

   ;; Directories in the graph that are not notes and should not count towards
   ;; churn or be described in a commit message. Defaults to where this repo is
   ;; conventionally mounted as a submodule.
   :excluded-paths [".hooks"]

   ;; Logseq rewrites files for reasons that carry no meaning: toggling a
   ;; block's fold state, or stamping an `id::` onto a block the moment it is
   ;; referenced elsewhere. Changes to these properties are not counted.
   :ignored-properties #{"collapsed" "id"}

   ;; Either of these is enough to let an auto-commit through: words changed,
   ;; or blocks touched. Two measures because a Logseq block is a single line
   ;; however long it is, so lines alone would miss a long new note entirely.
   :word-threshold 120
   :block-threshold 20

   ;; Backstop: commit regardless once changes have been held back this long,
   ;; so that a quiet week still produces commits (and therefore pushes).
   :max-defer-seconds (* 4 60 60)

   :model "claude-haiku-4-5-20251001"
   :max-tokens 400
   :api-timeout-ms 5000

   ;; Ceiling on the diff sent to the API. A commit that has been accumulating
   ;; for hours can be very large and a subject line does not need all of it.
   :max-diff-bytes 40000

   ;; When true, print the decision and the generated message but change
   ;; nothing. Useful for tuning the threshold against real edits.
   :dry-run false

   :push {:remote "origin"
          :branch "main"
          :min-interval (* 60 60)    ; never push more often than hourly
          :max-interval (* 3 60 60)  ; always push at least every three hours
          :churn-floor 150
          :file-floor 15
          :sample-size 400
          :min-buckets 20
          :cache-ttl (* 24 60 60)}})

(defn- read-file []
  (let [f (git/git-path config-filename)]
    (when (fs/exists? f)
      (try
        (edn/read-string (slurp f))
        (catch Exception e
          (throw (ex-info (str "Malformed " f ": " (ex-message e)) {})))))))

(def config
  (delay (let [user (read-file)]
           (-> (merge defaults user)
               (assoc :push (merge (:push defaults) (:push user)))))))

(defn value [& ks]
  (get-in @config ks))

(defn require-key
  "Like `value`, but throws with an actionable message if the key is absent."
  [& ks]
  (or (apply value ks)
      (throw (ex-info (format "%s is not set in %s"
                              (pr-str (vec ks))
                              (git/git-path config-filename))
                      {}))))
