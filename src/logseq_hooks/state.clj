(ns logseq-hooks.state
  "Small pieces of hook state kept inside the git directory: derived caches,
  and marker files whose mere existence (and age) carries the meaning."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [logseq-hooks.git :as git]))

;;; -------------------------------------------------------------- edn cache

(defn read-cache
  "Returns the cached value at `filename`, or nil if it is missing, older than
  `ttl-seconds`, or unreadable.

  A hook killed part-way through writing leaves truncated EDN behind, which
  would otherwise throw on every subsequent commit; treat that as a miss."
  [filename ttl-seconds]
  (let [f (git/git-path filename)]
    (when (and (fs/exists? f) (< (git/seconds-since-modified f) ttl-seconds))
      (try
        (edn/read-string (slurp f))
        (catch Exception _ nil)))))

(defn write-cache [filename value]
  (spit (git/git-path filename) (pr-str value))
  value)

(defn cached
  "Returns the cached value at `filename`, computing and storing it on a miss."
  [filename ttl-seconds compute]
  (or (read-cache filename ttl-seconds)
      (write-cache filename (compute))))

;;; ------------------------------------------------------------------ marker

(defn mark!
  "Creates `filename` if it does not exist, deliberately leaving an existing
  file's mtime alone: the age we care about is how long the marker has been
  standing, not when it was last touched."
  [filename]
  (let [f (git/git-path filename)]
    (when-not (fs/exists? f)
      (fs/create-file f))
    f))

(defn mark-age
  "Seconds since `filename` was created, or nil if there is no marker."
  [filename]
  (let [f (git/git-path filename)]
    (when (fs/exists? f)
      (git/seconds-since-modified f))))

(defn unmark! [filename]
  (fs/delete-if-exists (git/git-path filename)))

;;; --------------------------------------------------------------------- log

(def log-filename "logseq-hooks.log")

;; One line per auto-commit attempt adds up over years of half-hourly commits,
;; so keep one previous generation and drop anything older.
(def ^:private log-max-bytes (* 1024 1024))

(defn log!
  "Appends a timestamped line to the hook log in the git directory.

  Logseq discards hook output, so without this there is no record of why a
  commit was held back or got the fallback message. Never throws: losing a log
  line is better than losing a commit."
  [line]
  (try
    (let [f (git/git-path log-filename)]
      (when (and (fs/exists? f) (> (fs/size f) log-max-bytes))
        (fs/move f (git/git-path (str log-filename ".1")) {:replace-existing true}))
      (spit f (str (java.time.OffsetDateTime/now) " " line "\n") :append true))
    (catch Exception _ nil)))
