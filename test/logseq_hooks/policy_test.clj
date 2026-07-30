(ns logseq-hooks.policy-test
  (:require [clojure.test :refer [deftest is testing]]
            [logseq-hooks.policy :as policy]))

(def commit-config
  {:word-threshold 120
   :block-threshold 20
   :max-defer-seconds 14400})

(defn- commit [state]
  (first (policy/commit-decision state commit-config)))

(deftest manual-commits-are-untouched
  (testing "a hand-written message is left alone however large the change"
    (is (= :leave (commit {:auto? false :blocks 500 :words 9000})))))

(deftest a-long-note-in-one-block-is-committed
  (testing "the case line counting used to miss entirely"
    (is (= :rewrite (commit {:auto? true :blocks 1 :words 400})))))

(deftest a-thin-change-across-many-pages-is-committed
  (testing "few words, but plainly deliberate work"
    (is (= :rewrite (commit {:auto? true :blocks 30 :words 30})))))

(deftest small-changes-are-deferred
  (is (= :defer (commit {:auto? true :blocks 2 :words 8}))))

(deftest bookkeeping-is-reported-as-such
  (is (= [:defer "nothing but bookkeeping"]
         (policy/commit-decision {:auto? true :blocks 0 :words 0} commit-config))))

(deftest the-backstop-overrides-the-thresholds
  (testing "a quiet week must still produce commits and therefore pushes"
    (is (= :defer (commit {:auto? true :blocks 1 :words 5 :deferred-for 3600})))
    (is (= :rewrite (commit {:auto? true :blocks 1 :words 5 :deferred-for 18000})))))

(deftest reasons-are-pluralised
  (is (= [:defer "1 changed word in 1 block, below thresholds of 120/20"]
         (policy/commit-decision {:auto? true :blocks 1 :words 1} commit-config))))

;;; ------------------------------------------------------------------- pushing

(def push-config {:min-interval 3600 :max-interval 10800})
(def push-thresholds {:churn 150 :files 15})

(defn- push [state]
  (first (policy/push-decision state push-thresholds push-config)))

(deftest a-missing-remote-ref-always-pushes
  (is (= :push (push {:remote-age nil}))))

(deftest nothing-to-push-is-a-skip
  (is (= :skip (push {:remote-age 7200 :up-to-date? true :churn 0 :files 0}))))

(deftest staleness-beats-the-rate-limit
  (is (= :push (push {:remote-age 20000 :churn 1 :files 1}))))

(deftest the-rate-limit-beats-the-thresholds
  (testing "a large change just after a push still waits"
    (is (= :skip (push {:remote-age 60 :churn 9000 :files 90})))))

(deftest either-threshold-triggers-a-push
  (is (= :push (push {:remote-age 7200 :churn 200 :files 1})))
  (is (= :push (push {:remote-age 7200 :churn 1 :files 40})))
  (is (= :skip (push {:remote-age 7200 :churn 1 :files 1}))))
