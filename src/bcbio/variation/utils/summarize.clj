(ns bcbio.variation.utils.summarize
  "Collapse a multi-sample VCF file into a CSV, R data.frame ready, parameter summary."
  (:use [clojure.java.io]
        [ordered.map :only [ordered-map]]
        [bcbio.variation.callable :only [get-bed-source features-in-region]]
        [bcbio.variation.config :only [load-config]]
        [bcbio.variation.variantcontext :only [parse-vcf get-vcf-source]])
  (:require [clojure.data.csv :as csv]
            [incanter.stats :as istats]
            [bcbio.run.itx :as itx]))

(defn- flatten-vc-samples
  "Provide sample information from variant genotypes."
  [out vc attrs]
  (let [variant-types ["HET" "HOM_VAR"]]
    (letfn [(add-variant-totals [out gs]
              (let [counts (frequencies (map :type gs))]
                (reduce (fn [coll [k v]] (assoc coll k v))
                        out (map (fn [k] [k (get counts k 0)])
                                 variant-types))))
            (get-attr-avg [k gs]
              (istats/mean (->> gs
                                (filter #(contains? (set variant-types) (:type %)))
                                (map #(get-in % [:attributes k]))
                                (remove nil?)
                                (map #(Float/parseFloat %)))))
            (add-attr-avgs [out gs attrs]
              (reduce (fn [coll k] (assoc coll (str k "_sample_mean")
                                          (get-attr-avg k gs)))
                      out attrs))]
      (-> out
          (add-variant-totals (:genotypes vc))
          (add-attr-avgs (:genotypes vc) attrs)))))

(defn- flatten-vc-attrs
  "Extract attributes of interest from INFO field of variant."
  [out vc attrs]
  (reduce (fn [coll k] (assoc coll k (get-in vc [:attributes k])))
          out attrs))

(defn- flatten-vc-intervals
  "Check for presence of the variant in predefined intervals."
  [out vc intervals]
  (letfn [(check-intervals [vc bed-s]
            (if (empty? (features-in-region bed-s (:chr vc) (:start vc) (:end vc))) 0 1))]
    (reduce (fn [coll interval]
              (assoc coll (:name interval) (check-intervals vc (:source interval))))
            out intervals)))

(defn- flatten-vc
  "Provide tabular variant representation with provided attributes and sample information."
  [config vc]
  (-> (reduce (fn [coll k] (assoc coll k (get vc k)))
              (ordered-map) [:chr :start :id :type :qual])
      (flatten-vc-intervals vc (get config :intervals []))
      (flatten-vc-attrs vc (:attrs config))
      (flatten-vc-samples vc (:sample-attrs config))))

(defn- add-interval-retrievers
  [config]
  (letfn [(add-int-retriever [coll]
            (assoc coll :source (get-bed-source (:file coll))))]
    (assoc config :intervals (map add-int-retriever (:intervals config)))))

(defn vcf-to-table
  "Convert a VCF input to flattened CSV table with provided attributes."
  [vcf ref config]
  (let [out-file (str (itx/file-root vcf) "-variantsum.csv")]
    (when (itx/needs-run? out-file)
      (with-open [vcf-source (get-vcf-source vcf ref)
                  wtr (writer out-file)]
        (doseq [[i out] (map-indexed vector
                                     (map (partial flatten-vc (add-interval-retrievers config))
                                          (parse-vcf vcf-source)))]
          (when (= i 0)
            (csv/write-csv wtr [(map name (keys out))]))
          (csv/write-csv wtr [(vals out)])
          (.flush wtr))))
    out-file))

(defn vcf-to-table-config
  "Prep a set of VCF to table conversions from input configuration file."
  [config-file]
  (let [config (load-config config-file)]
    (doall
     (flatten
      (for [exp (:experiments config)]
        (for [call (:calls exp)]
          (vcf-to-table (:file call) (:ref exp) (:summary call))))))))

(defn -main [config-file]
  (vcf-to-table-config config-file))
