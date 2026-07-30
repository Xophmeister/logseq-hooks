(ns logseq-hooks.git
  "Thin wrappers over the git CLI, plus access to paths inside the git
  directory.

  Hooks shell out rather than using a git library: they are already being
  invoked by git and inherit its environment, so the CLI is both the cheapest
  and the most faithful option."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn git
  "Runs git with `args`, returning trimmed stdout, or nil if git exited non-zero."
  [& args]
  (let [{:keys [out exit]} (p/sh (into ["git"] args))]
    (when (zero? exit)
      (str/trim out))))

(defn git!
  "Like `git`, but throws if the command fails."
  [& args]
  (-> (p/sh (into ["git"] args))
      p/check
      :out
      str/trim))

(def git-dir
  "Absolute path to the git directory, resolved once per process."
  (delay (fs/file (git! "rev-parse" "--absolute-git-dir"))))

(defn git-path
  "Absolute path to `filename` inside the git directory.

  Anything kept here is invisible to both the repository and Logseq's file
  watcher, which makes it the right home for secrets, caches and markers."
  [filename]
  (fs/file @git-dir filename))

(defn epoch-now []
  (.getEpochSecond (java.time.Instant/now)))

(defn seconds-since-modified [f]
  (- (epoch-now) (quot (.toMillis (fs/last-modified-time f)) 1000)))

(defn head-sha []
  (git "rev-parse" "HEAD"))
