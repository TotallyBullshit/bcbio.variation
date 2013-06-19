;; Test code for idempotent file running

(ns bcbio.variation.test.run
  (:use [midje.sweet]
        [bcbio.run.itx])
  (:require [me.raynes.fs :as fs]))

(fact "Idempotent processing of files"
  (needs-run? "project.clj") => false
  (needs-run? "noexist.txt") => true
  (needs-run? "project.clj" "README.md") => false
  (needs-run? ["project.clj" "README.md"]) => false
  (needs-run? "project.clj" "noexist.txt") => true)

(let [kwds {:test "new"}]
  (fact "Allow special keywords in argument paths."
    (subs-kw-files [:test] kwds) => ["new"]
    (subs-kw-files [:test "stay"] kwds) => ["new" "stay"]
    (subs-kw-files [:nope "stay"] kwds) => [:nope "stay"]))

(facts "Manipulating file paths"
  (add-file-part "test.txt" "add") => "test-add.txt"
  (add-file-part "/full/test.txt" "new") => "/full/test-new.txt"
  (file-root "/full/test.txt") => "/full/test"
  (remove-zip-ext "test.txt") => "test.txt"
  (remove-zip-ext "test.txt.gz") => "test.txt"
  (remove-zip-ext "test.tar.gz") => "test")
