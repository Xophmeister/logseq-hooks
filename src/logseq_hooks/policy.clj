(ns logseq-hooks.policy
  "The decisions the two hooks make, separated from the git and network work
  that feeds them.

  Everything here is pure and takes plain maps, so the behaviour that actually
  matters -- when a commit is held back, when a push happens -- can be tested
  without a repository, an API key or a remote.")

(defn- quantity [n unit]
  (format "%d %s%s" n unit (if (= 1 n) "" "s")))

(defn commit-decision
  "Returns [action reason], where action is :leave, :rewrite or :defer."
  [{:keys [auto? blocks words deferred-for]}
   {:keys [word-threshold block-threshold max-defer-seconds]}]
  (cond
    (not auto?)
    [:leave "not an auto-commit"]

    ;; Either measure is sufficient on its own: a long new note is few blocks,
    ;; and an edit swept across many pages is few words.
    (>= words word-threshold)
    [:rewrite (format "%s (threshold %d)" (quantity words "changed word") word-threshold)]

    (>= blocks block-threshold)
    [:rewrite (format "%s (threshold %d)" (quantity blocks "changed block") block-threshold)]

    (and deferred-for (>= deferred-for max-defer-seconds))
    [:rewrite (format "held back for %.1f hours" (/ deferred-for 3600.0))]

    (zero? blocks)
    [:defer "nothing but bookkeeping"]

    :else
    [:defer (format "%s in %s, below thresholds of %d/%d"
                    (quantity words "changed word") (quantity blocks "block")
                    word-threshold block-threshold)]))

(defn push-decision
  "Returns [:push reason] or [:skip reason]."
  [{:keys [remote-age up-to-date? churn files]}
   thresholds
   {:keys [min-interval max-interval]}]
  (cond
    (nil? remote-age) [:push "no remote tracking ref"]
    up-to-date? [:skip "already up to date"]
    (>= remote-age max-interval) [:push "remote is stale"]
    (< remote-age min-interval) [:skip "pushed too recently"]
    (>= churn (:churn thresholds)) [:push (format "%d lines (p90 %d)" churn (:churn thresholds))]
    (>= files (:files thresholds)) [:push (format "%d files (p90 %d)" files (:files thresholds))]
    :else [:skip "below thresholds"]))
