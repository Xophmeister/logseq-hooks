(ns logseq-hooks.message
  "Turning a staged diff into a commit message.

  Everything here is deliberately pure apart from `generate`, so the prompt and
  the sanitising can be exercised at a REPL without a repository or an API key."
  (:require [clojure.string :as str]
            [logseq-hooks.anthropic :as anthropic]
            [logseq-hooks.churn :as churn]))

(def subject-limit 72)

;;; ------------------------------------------------------------- page naming

(defn page-name
  "Best-effort page title for a graph-relative path.

  Logseq's filename sanitising is more involved than this -- it escapes several
  characters -- but the common cases are a plain page file and a dated journal
  file and the model is given the raw paths as well."
  [path]
  (let [base (-> path
                 (str/replace #"^.*/" "")
                 (str/replace #"\.(md|org)$" ""))]
    (if (str/starts-with? path "journals/")
      (str/replace base "_" "-")
      base)))

;;; --------------------------------------------------------------- fallback

(defn- by-size [entries]
  (sort-by (fn [{:keys [added deleted]}] (- (+ added deleted))) entries))

(defn fallback-subject
  "A dull but accurate subject, used when the API is unavailable. Better than
  `Auto saved by Logseq` and it never fails.

  Quantified in words rather than lines: a Logseq block is one line however long
  it is, so a line count here would be actively misleading."
  [entries words]
  (let [{:keys [files]} (churn/totals entries)
        [primary] (by-size entries)]
    (cond
      (nil? primary) "Auto commit: no tracked changes"
      (= 1 files) (format "%s (%d words changed)" (page-name (:path primary)) words)
      :else (format "%s and %d other page%s (%d words changed)"
                    (page-name (:path primary))
                    (dec files)
                    (if (= 2 files) "" "s")
                    words))))

;;; ----------------------------------------------------------------- prompt

(def system-prompt
  (str/join
   "\n"
   ["You write git commit messages for a personal Logseq knowledge graph that is published as a website."
    "Each published page links to its own commit history, so the subject line is often the only description"
    "a reader ever sees of what changed on that page."
    ""
    "Rules:"
    "- First line: at most 72 characters, no trailing full stop."
    "- Name the page or pages affected, using the page title rather than the file path, then say"
    "  substantively what changed. Refer to journal pages by their date."
    "- Prefer the specific over the general: 'Topiary: notes on tree-sitter query precedence',"
    "  not 'Update Topiary notes'. Never use filler such as 'Auto saved', 'Update' or 'Various changes'."
    "- If several pages changed, lead with the most substantial one and mention the rest in the body."
    "- Optional body after one blank line: up to three short lines, one per page or theme."
    "  Omit the body entirely if the subject line already says everything."
    "- Describe only what the diff shows. Do not speculate about intent or invent detail."
    "- Output the commit message as plain text and nothing else: no preamble, no code fences, no quoting."]))

(defn build-prompt
  "Assembles the user turn. `diff` is truncated by the caller; when it has been,
  say so, so the model does not describe the remainder with false confidence."
  [{:keys [stat diff truncated?]}]
  (str/join "\n"
            (cond-> ["Files changed:" "" stat "" "Diff:" "" diff]
              truncated? (conj "" "[The diff was truncated; there are further changes not shown.]"))))

;;; -------------------------------------------------------------- sanitising

(defn- strip-fences [s]
  (-> s
      (str/replace #"(?m)^\s*```[a-zA-Z]*\s*$" "")
      str/trim))

(defn- shorten [s limit]
  (if (<= (count s) limit)
    s
    ;; Trim back to a word boundary rather than cutting mid-word.
    (let [cut (subs s 0 limit)]
      (str/replace cut #"\s+\S*$" ""))))

(defn sanitise
  "Coerces model output into a well-formed commit message, or nil if there is
  nothing usable left."
  [text]
  (let [lines (->> (strip-fences (or text ""))
                   str/split-lines
                   (drop-while str/blank?))
        subject (-> (or (first lines) "")
                    (str/replace #"^(?:Subject|Commit message)\s*:\s*" "")
                    (str/replace #"^[\"'`]|[\"'`]$" "")
                    (str/replace #"\.$" "")
                    str/trim
                    (shorten subject-limit))
        body (->> (rest lines)
                  (drop-while str/blank?)
                  (str/join "\n")
                  str/trimr)]
    (when (seq subject)
      (if (seq body)
        (str subject "\n\n" body "\n")
        (str subject "\n")))))

;;; ------------------------------------------------------------------ public

(defn generate
  "Returns [message reason]. Falls back to `fallback-subject` on any failure,
  with the reason carried through so the hook can log why."
  [{:keys [entries words stat diff truncated? api-key model max-tokens api-timeout-ms]}]
  (let [{:keys [text error]} (anthropic/message
                              {:api-key api-key
                               :model model
                               :max-tokens max-tokens
                               :timeout-ms api-timeout-ms
                               :system system-prompt
                               :prompt (build-prompt {:stat stat
                                                      :diff diff
                                                      :truncated? truncated?})})]
    (if-let [message (and (nil? error) (sanitise text))]
      [message "generated"]
      [(str (fallback-subject entries words) "\n")
       (str "fell back to a stat summary: " (or error "unusable response"))])))
