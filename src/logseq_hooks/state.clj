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
