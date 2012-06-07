(ns bcbio.variation.evaluate
  "Provide high level summary evaluation of variant results, building off GATK VariantEval."
  (:import [org.broadinstitute.sting.gatk.report GATKReport])
  (:use [clojure.java.io]
        [ordered.map :only [ordered-map]])
  (:require [clojure.string :as string]
            [doric.core :as doric]
            [bcbio.run.itx :as itx]
            [bcbio.run.broad :as broad]))

(defn calc-variant-eval-metrics
  "Compare two variant files with GenotypeConcordance in VariantEval"
  [sample vcf1 vcf2 ref & {:keys [out-base intervals]}]
  (let [file-info {:out-eval (str (itx/file-root (if (nil? out-base) vcf1 out-base)) ".eval")}
        args (concat
              ["-R" ref
               "--out" :out-eval
               "--eval" vcf1
               "--comp" vcf2
               "--sample" sample
               "--doNotUseAllStandardModules"
               "--evalModule" "CompOverlap"
               "--evalModule" "CountVariants"
               "--evalModule" "GenotypeConcordance"
               "--evalModule" "TiTvVariantEvaluator"
               "--evalModule" "ValidationReport"
               "--stratificationModule" "Sample"
               "--stratificationModule" "Filter"]
              (broad/gatk-cl-intersect-intervals intervals))]
    (broad/run-gatk "VariantEval" args file-info {:out [:out-eval]})
    (:out-eval file-info)))

(defn- calc-summary-eval-metrics
  "Run VariantEval providing summary information for a VCF file"
  [vcf ref dbsnp intervals cmp-interval-file]
  (let [file-info {:out-eval (str (itx/file-root vcf) "-summary.eval")}
        args (concat
              ["-R" ref
               "--out" :out-eval
               "--eval" vcf
               "--doNotUseAllStandardModules"
               "--evalModule" "CompOverlap"
               "--evalModule" "CountVariants"
               "--evalModule" "ThetaVariantEvaluator"
               "--evalModule" "TiTvVariantEvaluator"
               "--evalModule" "ValidationReport"
               "--evalModule" "VariantSummary"
               "--stratificationModule" "Filter"]
              (broad/gatk-cl-intersect-intervals intervals)
              (if (nil? dbsnp) [] ["--dbsnp" dbsnp])
              (if (nil? cmp-interval-file)
                []
                ["--stratificationModule" "IntervalStratification"
                 "--stratIntervals" cmp-interval-file]))]
    (broad/run-gatk "VariantEval" args file-info {:out [:out-eval]})
    (:out-eval file-info)))

(defn organize-gatk-report-table
  "Parses a GATK output table and filters based on supplied input function."
  [eval-file table-name filter-fn]
  (let [cols (-> (GATKReport. (file eval-file))
                 (.getTable table-name)
                 .getColumns
                 rest)
        headers (map #(-> % (.getColumnName) keyword) cols)]
    (->> (for [i (range (count (.values (first cols))))]
           (zipmap headers
                   (map #(nth (vec (.values %)) i) cols)))
         (filter filter-fn))))

(defn summary-eval-metrics
  "Provide high level summary metrics of a single variant file."
  [vcf ref & {:keys [intervals cmp-intervals dbsnp]}]
  (let [group-metrics (concat [:Novelty] (if intervals [:IntervalStratification] []))
        val-metrics [:nSamples :nProcessedLoci :nSNPs :TiTvRatio :TiTvRatioPerSample
                     :nSNPsPerSample :SNPNoveltyRate]
        count-metrics [:nSNPs :nInsertions :nDeletions :nHets :nHomVar :hetHomRatio]]
    (letfn [(all-called? [x]
              (and (= (:Filter x) "called")
                   (contains? #{nil "all"} (:Sample x))))
            (select-keys-ordered [metrics coll]
              (ordered-map (map (fn [x] [x (get coll x)]) metrics)))
            (get-table-info [eval-file table metrics]
              (->> (organize-gatk-report-table eval-file table all-called?)
                   (map (partial select-keys-ordered metrics))))
            (merge-line [vals]
              (reduce (fn [outer tbl-vals]
                        (reduce (fn [inner [k v]]
                                  (assoc inner k v))
                                outer (remove #(contains? (set group-metrics) %1) tbl-vals)))
                      (first vals) (rest vals)))
            (merge-tables [& tbls]
              (map merge-line
                   (partition (count tbls) (apply interleave tbls))))]
      (let [eval-file (calc-summary-eval-metrics vcf ref dbsnp
                                                 intervals cmp-intervals)]
        (merge-tables
         (get-table-info eval-file "CountVariants" (concat group-metrics count-metrics))
         (get-table-info eval-file "VariantSummary" (concat group-metrics val-metrics)))))))

(defn write-summary-eval-metrics
  "Write high level summary metrics to CSV file."
  [vcf ref & {:keys [intervals cmp-intervals dbsnp]}]
  (let [out-file (str (itx/file-root vcf) "-summary.csv")]
    (let [metrics (summary-eval-metrics vcf ref :intervals intervals :cmp-intervals cmp-intervals
                                        :dbsnp dbsnp)]
      (with-open [wtr (writer out-file)]
        (.write wtr (str (string/join "," (map name (-> metrics first keys))) "\n"))
        (doseq [xs metrics]
          (.write wtr (str (string/join "," (vals xs)) "\n")))))))

(defn -main
  ([vcf ref dbsnp intervals cmp-intervals]
     (write-summary-eval-metrics vcf ref :intervals intervals :cmp-intervals cmp-intervals
                                 :dbsnp dbsnp))
  ([vcf ref dbsnp cmp-intervals]
     (write-summary-eval-metrics vcf ref :cmp-intervals cmp-intervals
                                 :dbsnp dbsnp))
  ([vcf ref dbsnp]
     (write-summary-eval-metrics vcf ref :dbsnp dbsnp)))
