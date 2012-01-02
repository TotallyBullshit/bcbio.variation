;; Identify callable bases from a BAM alignment file
;; Help differentiate positions where we can not assess variation

(ns bcbio.variation.callable
  (:import [org.broad.tribble.bed BEDCodec]
           [org.broad.tribble.index IndexFactory]
           [org.broad.tribble.source BasicFeatureSource])
  (:use [clojure.java.io])
  (:require [fs.core :as fs]
            [bcbio.run.itx :as itx]
            [bcbio.run.broad :as broad]))

(defn identify-callable [align-bam ref]
  "Identify callable bases from the provided alignment file."
  (let [file-info {:out-bed (format "%s-callable.bed" (itx/file-root align-bam))
                   :out-summary (format "%s-callable-summary.txt" (itx/file-root align-bam))}
        args ["-R" ref
              "-I" align-bam
              "--out" :out-bed
              "--summary" :out-summary]]
    (broad/index-bam align-bam)
    (broad/run-gatk "CallableLoci" args file-info {:out [:out-bed :out-summary]})
    (:out-bed file-info)))

(defn features-in-region [source space start end]
  (for [f (.query source space start end)]
    {:chr (.getChr f)
     :start (.getStart f)
     :end (.getEnd f)
     :name (.getName f)
     :score (.getScore f)
     :strand (.getStrand f)}))

(defn callable-interval-tree [align-bam ref]
  "Retrieve an IntervalTree to retrieve information on callability in a region."
  (let [bed-file (identify-callable align-bam ref)
        batch-size 500
        idx (IndexFactory/createIntervalIndex (file bed-file) (BEDCodec.) batch-size)]
    (BasicFeatureSource. bed-file idx (BEDCodec.))))

(defn callable-checker [align-bam ref]
  (let [source (callable-interval-tree align-bam ref)]
    (letfn [(is-callable? [space start end]
              (> (count (filter #(= (:name %) "CALLABLE")
                                (features-in-region source space start end)))
                 0))]
      is-callable?)))
