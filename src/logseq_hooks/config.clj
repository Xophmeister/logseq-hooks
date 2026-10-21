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

   ;; Paths in the graph that are not notes and should not count towards churn
   ;; or be described in a commit message: chiefly wherever this repo is
   ;; mounted, since a submodule pointer bump is a real diff but not a note.
   ;;
   ;; Empty by default, because the mount point is a deployment fact the
   ;; library cannot know. `bin/install` writes the real one into the config it
   ;; seeds; guessing `.hooks` here would only be right by coincidence and
   ;; wrong silently.
   :excluded-paths []

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

   ;; A ceiling, not a target: a reply is a few dozen tokens, but on models
   ;; that think by default the thinking is paid for out of this budget too.
   ;; Running out is reported as a failure rather than committing a message
   ;; cut off mid-phrase, so err on the generous side.
   :max-tokens 2000

   ;; Sent as the request's `thinking` and `output_config.effort` parameters
   ;; when set; nil omits them and leaves each model to its own defaults, which
   ;; is the only setting every model accepts (Haiku 4.5, for one, rejects
   ;; effort outright). Prefer lowering :effort to disabling thinking: a model
   ;; that thinks by default and is told not to tends to deliberate in the reply
   ;; instead, drafts and character counts included.
   :thinking nil
   :effort nil
   :api-timeout-ms 5000

   ;; Natural language and variant the commit message is written in. Defaults
   ;; to the author's; set your own, or nil to leave it to the model. The model
   ;; is asked to follow the punctuation conventions of whatever this names.
   :language "British English"

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
