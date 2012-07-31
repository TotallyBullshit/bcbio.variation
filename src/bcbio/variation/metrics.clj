(ns bcbio.variation.metrics
  "Accumulate and analyze metrics associated with each variant.
   This provides summaries intended to identify characteristic
   metrics to use for filtering."
  (:use [clojure.java.io]
        [clojure.set]
        [bcbio.variation.variantcontext :only [parse-vcf get-vcf-iterator]]
        [clojure.string :only [split-lines]]
        [clj-ml.data :only [make-dataset]]
        [clj-ml.classifiers :only [make-classifier classifier-train]]
        [ordered.set :only [ordered-set]])
  (:require [incanter.stats :as istats]
            [doric.core :as doric]))

;; ## Convenience functions

(defn- to-float [x]
  (if (number? x)
    x
    (try
      (Float/parseFloat x)
      (catch Exception e nil))))

(defn passes-filter?
  "Check if a VariantContext is not filtered."
  [vc]
  (= (count (:filters vc)) 0))

(defn nonref-passes-filter?
  "Check if a variant context is not filter and is not a reference call."
  [vc]
  (and (passes-filter? vc)
       (every? #(contains? #{"HET" "HOM_VAR"} (:type %)) (:genotypes vc))))

(defn get-vc-metrics
  "Retrieve numeric metrics associated with VariantContext."
  [vc]
  (reduce (fn [coll [k v]]
            (if-let [num-v (to-float v)]
              (assoc coll k num-v)
              coll))
   {}
   (assoc (:attributes vc) "QUAL" (:qual vc))))

;; ## Summary metrics
;; Provide a summary-style presentation of distribution of metrics values.

(def header [{:name :metric}
             {:name :count}
             {:name :min :format #(format "%.2f" %)}
             {:name :pct25 :format #(format "%.2f" %)}
             {:name :median :format #(format "%.2f" %)}
             {:name :pct75 :format #(format "%.2f" %)}
             {:name :max :format #(format "%.2f" %)}])

(defn summary-stats [key vals]
  "Provide summary statistics on a list of values."
  (zipmap (map :name header)
          (concat [key (count vals)]
                  (istats/quantile vals))))

(defn- raw-vcf-stats
  "Accumulate raw statistics associated with variant calls from input VCF."
  [vcf-file ref-file]
  (letfn [(collect-attributes [collect [k v]]
            (if-not (nil? (to-float v))
              (assoc collect k (cons (to-float v) (get collect k [])))
              collect))
          (collect-vc [collect vc]
            (assoc (reduce collect-attributes collect (:attributes vc))
              "QUAL" (cons (:qual vc)
                           (get collect "QUAL" []))))]
    (with-open [vcf-iter (get-vcf-iterator vcf-file ref-file)]
      (reduce collect-vc {} (filter passes-filter? (parse-vcf vcf-iter))))))

(defn vcf-stats
  "Collect summary statistics associated with variant calls."
  [vcf-file ref-file]
  (let [raw-stats (raw-vcf-stats vcf-file ref-file)]
    (map #(apply summary-stats %) (sort-by first raw-stats))))

(defn write-summary-table [stats & {:keys [wrtr]
                                    :or {wrtr (writer System/out)}}]
  (.write wrtr (str (doric/table header stats) "\n")))

;; ## Classify
;; Provide metrics for files in preparation for automated
;; classification.

(defn- get-file-metrics
  "Collect classification metrics from a single VCF file."
  [ref-file vcf-file]
  (letfn [(has-nil-names [metrics all-metrics all-names]
            (let [test-names (union (-> metrics keys set) all-names)]
              (apply union
                     (map (fn [xs] (set (keep #(when (nil? (get xs %)) %) test-names)))
                          (cons metrics (take-last 10 all-metrics))))))
          (classifier-metrics [coll vc]
            (let [cur-metrics (get-vc-metrics vc)]
              (-> coll
                  (assoc :rows (cons cur-metrics (:rows coll)))
                  (assoc :names (union (-> cur-metrics keys set) (:names coll)))
                  (assoc :nil-names (union (has-nil-names cur-metrics (:rows coll) (:names coll))
                                           (:nil-names coll))))))
          (prep-table [{rows :rows names :names nil-names :nil-names}]
            (let [sort-names (sort (vec names))]
              {:cols sort-names
               :with-nil-cols nil-names
               :rows (map (fn [x]
                            (map #(get x %) sort-names))
                          rows)}))]
    (with-open [vcf-iter (get-vcf-iterator vcf-file ref-file)]
      (prep-table
       (reduce classifier-metrics {:rows [] :names #{} :nil-names #{}}
               (filter passes-filter? (parse-vcf vcf-iter)))))))

(defn get-vcf-classifier-metrics
  "Collect metrics from multiple vcf files into tables suitable for
  classification algorithms."
  [ref-file vcf-files & {:keys [remove-nil-cols]
                         :or {remove-nil-cols true}}]
  (letfn [(get-shared-cols [xs]
            (-> (apply intersection (map #(set (:cols %)) xs))
                sort
                vec))
          (filter-by-cols [orig-cols want-cols]
            (let [check-cols (set want-cols)
                  want (set (keep-indexed #(if (contains? check-cols %2) %1) orig-cols))]
              (fn [xs]
                (keep-indexed #(when (contains? want %1) %2) xs))))
          (subset-file-metrics [shared-cols nil-cols {cols :cols rows :rows}]
            (let [ready-cols (if-not remove-nil-cols shared-cols
                                     (remove #(contains? nil-cols %) shared-cols))
                  row-filter (filter-by-cols cols ready-cols)]
              {:cols ready-cols
               :rows (remove #(not= (count %) (count ready-cols)) (map row-filter rows))}))]
    (let [file-metrics (map (partial get-file-metrics ref-file) vcf-files)
          shared-cols (get-shared-cols file-metrics)
          nil-cols (apply union (map #(set (:with-nil-cols %)) file-metrics))]
      (map (partial subset-file-metrics shared-cols nil-cols) file-metrics))))

(defn- parse-classifier-nodes
  "Retrieve classification metrics from a tree based classifier.
  Metric ordering is relative to the usefulness in classifying."
  [classifier metrics]
  (->> classifier
       .graph
       split-lines
       (map #(re-find #"label=\"(\w+)\"" %))
       (map second)
       flatten
       (remove nil?)
       (filter #(contains? (set metrics) %))
       (apply ordered-set)))

(defn classify-decision-tree
  "Classify VCF files with INFO metrics using a decision tree classifier."
  [metrics]
  (letfn [(prep-one-dataset [rows i]
            (map #(conj (vec %) (str i)) rows))
          (prep-dataset [metrics]
            (make-dataset "ds" (conj (-> metrics first :cols vec)
                                     {:c (map str (range (count metrics)))})
                          (apply concat (map-indexed #(prep-one-dataset (:rows %2) %1) metrics))
                          {:class :c}))]
    (let [ds (prep-dataset metrics)
          c (-> (make-classifier :decision-tree :c45)
                (classifier-train ds))]
      (vec (parse-classifier-nodes c (-> metrics first :cols))))))

(defn merge-classified-metrics
  "Merge multiple classification approaches into a set of final metrics.
  `in-metrics` contains ordered best metric classifiers from the different
  approaches. Returns interleaved metrics ranked by present in these
  classifiers. "
  [in-metrics]
  (loop [cur in-metrics
         final (ordered-set)]
    (if (every? empty? cur)
      {:top-metrics (vec final)}
      (recur (map rest cur)
             (reduce #(conj %1 %2) final (remove nil? (map first cur)))))))

(defn ml-on-vcf-metrics
  "Apply machine learning/classification approaches to distinguish useful
  metrics distinguishing VCF files."
  [ref-file vcf-files]
  (letfn [(run-classifier [remove-nil-cols]
            (-> (get-vcf-classifier-metrics ref-file vcf-files :remove-nil-cols remove-nil-cols)
                classify-decision-tree))]
    (merge-classified-metrics (map run-classifier [true false]))))
