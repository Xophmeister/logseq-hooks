(ns logseq-hooks.churn
  "Measuring how much work a diff represents.

  Two different measures live here, because the hooks ask different questions.
  The push hook wants raw volume, so it counts every changed line via
  `--numstat`: cheap and its thresholds are self-calibrating percentiles over
  the same measure, so a systematic undercount does not mislead it.

  The commit hook wants to know whether anything was actually *said*, which
  lines measure badly; a Logseq block is one line however long it is. So it
  counts blocks touched and words changed, discarding Logseq's bookkeeping
  properties. That last part is a judgement about line content and therefore
  cannot be expressed as a pathspec."
  (:require [clojure.string :as str]
            [logseq-hooks.git :as git]))

;;; --------------------------------------------------------------- pathspecs

;; Logseq writes a backup of every file it touches and pasted images land in
;; assets/. Both correlate with real edits, so they would quietly inflate the
;; statistics. Excluded here as well as in .gitignore, since older commits may
;; predate the ignore rules.
(def backup-exclusions
  [":(exclude)logseq/bak/**"
   ":(exclude)logseq/.recycle/**"])

;; Binaries contribute no churn, but they are exactly the file-count spikes the
;; push hook wants to catch; so assets are excluded from one measure and not
;; the other.
(def churn-exclusions (conj backup-exclusions ":(exclude)assets/**"))

(defn exclude
  "Turns graph-relative directory names into exclusion pathspecs.

  Used for deployment details the library cannot know: chiefly the directory
  this repo is mounted at, since a submodule pointer bump is a real diff in the
  graph but is not a note.

  Two pathspecs per path, because a submodule appears in a diff as the gitlink
  itself rather than as anything beneath it, so `dir/**` alone does not match."
  [paths]
  (into [] (mapcat (fn [path] [(str ":(exclude)" path)
                               (str ":(exclude)" path "/**")]))
        paths))

(defn pathspec
  "Prefixes `exclusions` with an explicit positive pathspec.

  A pathspec list containing only exclusions is ambiguous -- depending on the
  git version it can match everything or nothing -- so say what is included."
  [exclusions]
  (into ["."] exclusions))

;;; ----------------------------------------------------------------- numstat

(defn- count-or-zero
  "--numstat reports `-` for binary files."
  [s]
  (if (= "-" s) 0 (parse-long s)))

(defn parse-numstat
  "Parses `added\tdeleted\tpath` lines into {:added :deleted :path} maps."
  [lines]
  (keep (fn [line]
          (when-not (str/blank? line)
            (let [[added deleted path] (str/split line #"\t" 3)]
              {:added (count-or-zero added)
               :deleted (count-or-zero deleted)
               :path path})))
        lines))

(defn totals
  "Sums numstat entries into {:churn n :files n}. Binary files contribute a
  file but no churn."
  [entries]
  (reduce (fn [acc {:keys [added deleted]}]
            (-> acc
                (update :files inc)
                (update :churn + added deleted)))
          {:churn 0 :files 0}
          entries))

(defn staged-entries
  "Numstat entries for the staged tree, ignoring `exclusions`."
  [exclusions]
  (->> (apply git/git! "diff" "--cached" "--numstat" "--" (pathspec exclusions))
       str/split-lines
       parse-numstat))

;;; --------------------------------------------------------- semantic churn

(defn- property-line-pattern
  "Matches a diff line whose only content is one of `properties`.

  Logseq indents nested blocks with tabs and properties sit on their own line
  directly beneath their block, so allow leading whitespace."
  [properties]
  (re-pattern (str "^[+-]\\s*(?:" (str/join "|" (map #(java.util.regex.Pattern/quote %) properties))
                   ")::")))

(defn- change-line?
  "True for added or removed content lines, excluding the `+++`/`---` file
  headers that share their prefix."
  [line]
  (and (re-find #"^[+-]" line)
       (not (re-find #"^(\+\+\+|---)" line))))

(defn changed-blocks
  "Counts changed lines in the staged tree, discarding lines that only set one
  of `ignored-properties`.

  A Logseq block is normally one line, so this is a count of blocks touched:
  a measure of the *breadth* of a change. Uses -U0 so that unchanged context is
  not counted."
  [exclusions ignored-properties]
  (let [pattern (property-line-pattern ignored-properties)]
    (->> (apply git/git! "diff" "--cached" "-U0" "--" (pathspec exclusions))
         str/split-lines
         (filter change-line?)
         (remove #(re-find pattern %))
         count)))

;; In --word-diff=porcelain output, changed word runs are lines prefixed with
;; `+` or `-`, unchanged runs with a space, and `~` marks a source newline. The
;; file headers share the `+`/`-` prefix, so they have to be excluded by shape;
;; each pattern requires a trailing space, which a markdown rule (`---`) lacks.
(def ^:private diff-header-pattern
  #"^(?:diff |index |--- |\+\+\+ |@@ |old mode |new mode |new file |deleted file |similarity |rename |Binary )")

(defn- word-runs
  "Pairs of [op text] for each changed word run in porcelain word-diff output."
  [out]
  (keep (fn [line]
          (when-not (re-find diff-header-pattern line)
            (when (re-find #"^[+-]" line)
              [(subs line 0 1) (subs line 1)])))
        (str/split-lines out)))

(defn changed-words
  "Counts words added or removed in the staged tree.

  Word-level rather than line-level because a Logseq block can be a paragraph
  long: counting lines would score a 400-word new block as 1 and -- worse --
  would score a two-word correction inside that block as 2 whole lines, since
  a modified line appears on both sides of the diff. Asking git for a word diff
  fixes both directions at once.

  Filtering by property here is approximate: word-diff dissolves line structure,
  so this drops runs that *begin* with an ignored property. `changed-blocks` is
  the precise filter of the two."
  [exclusions ignored-properties]
  (let [pattern (property-line-pattern ignored-properties)
        out (apply git/git! "diff" "--cached" "-U0" "--word-diff=porcelain"
                   "--" (pathspec exclusions))]
    (->> (word-runs out)
         (remove (fn [[op text]] (re-find pattern (str op text))))
         (mapcat (fn [[_ text]] (re-seq #"\S+" text)))
         count)))

(defn semantic-churn
  "How much was actually *said* by the staged changes: {:blocks n :words n}.

  Two measures because they miss in opposite directions. Blocks catch a change
  spread thinly over the graph -- renaming a tag across thirty pages -- which is
  barely any words. Words catch a change concentrated in one place -- a long new
  note -- which is barely any blocks."
  [exclusions ignored-properties]
  {:blocks (changed-blocks exclusions ignored-properties)
   :words (changed-words exclusions ignored-properties)})
