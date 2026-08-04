(ns logseq-hooks.message-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [logseq-hooks.message :as message]))

;;; ---------------------------------------------------------------- sanitising

(deftest fences-are-stripped
  (is (= "Topiary: notes on query precedence\n"
         (message/sanitise "```\nTopiary: notes on query precedence\n```"))))

(deftest labels-quotes-and-trailing-stops-are-stripped
  (is (= "Fixed the thing\n" (message/sanitise "Subject: \"Fixed the thing.\""))))

(deftest leading-blank-lines-are-dropped
  (is (= "A subject\n" (message/sanitise "\n\n   \nA subject"))))

(deftest a-body-is-preserved-after-one-blank-line
  (is (= "Subject line\n\n- first point\n- second point\n"
         (message/sanitise "Subject line\n\n- first point\n- second point"))))

(deftest an-overlong-subject-is-cut-at-a-word-boundary
  (let [subject (str/trim (message/sanitise (str/join " " (repeat 40 "word"))))]
    (is (<= (count subject) message/subject-limit))
    (is (str/ends-with? subject "word") "should not cut mid-word")))

(deftest an-overlong-subject-ends-on-a-clause-boundary
  (testing "cut at the last comma in budget rather than trailing off after it"
    (is (= "Link \"Peer review\" section headings across contributor pages\n"
           (message/sanitise
            "Link \"Peer review\" section headings across contributor pages, mark them reviewed")))))

(deftest an-overlong-subject-does-not-end-on-a-dangling-connective
  (testing "an early colon is a page separator, not a clause boundary, so keep it"
    (is (= "Facundo Dominguez: add detailed peer feedback on strategic thinking\n"
           (message/sanitise
            "Facundo Dominguez: add detailed peer feedback on strategic thinking and reviewing")))))

(deftest unusable-output-yields-nil
  (testing "so that generate can fall back rather than commit an empty message"
    (is (nil? (message/sanitise "")))
    (is (nil? (message/sanitise "   \n\n  ")))
    (is (nil? (message/sanitise nil)))))

;;; --------------------------------------------------------------- page naming

(deftest journal-files-are-named-by-date
  (is (= "2026-07-30" (message/page-name "journals/2026_07_30.md"))))

(deftest page-files-keep-their-hyphens
  (testing "only journals have underscores rewritten"
    (is (= "Tree-sitter" (message/page-name "pages/Tree-sitter.md")))
    (is (= "Some_page" (message/page-name "pages/Some_page.md")))))

;;; ----------------------------------------------------------------- fallback

(def entries
  [{:path "pages/Topiary.md" :added 40 :deleted 2}
   {:path "journals/2026_07_30.md" :added 1 :deleted 0}])

(deftest the-fallback-leads-with-the-largest-change
  (is (= "Topiary and 1 other page (83 words changed)"
         (message/fallback-subject entries 83))))

(deftest the-fallback-is-quantified-in-words-not-lines
  (testing "a line count would be misleading: a block is one line however long"
    (is (str/includes? (message/fallback-subject [(first entries)] 400) "400 words"))))

(deftest the-fallback-survives-an-empty-diff
  (is (string? (message/fallback-subject [] 0))))
