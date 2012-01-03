;; Generate comparisons between two sets of variant calls
;; Utilizes GATK walkers to generate detailed and summary statistics
;; about two sets of calls
;; - Identify non-callable regions with CallableLociWalker
;; - Combine variants from two samples
;; - Use VariantEval to calculate overall concordance statistics
;; - Provide output for concordant and discordant regions for
;;   detailed investigation

(ns bcbio.variation.compare
  (:use [bcbio.variation.variantcontext :only [parse-vcf write-vcf-w-template]]
        [bcbio.variation.stats :only [vcf-stats write-summary-table]]
        [bcbio.variation.report :only [concordance-report-metrics
                                       write-concordance-metrics]]
        [bcbio.variation.combine :only [combine-variants create-merged
                                        select-by-sample]]
        [bcbio.variation.annotation :only [add-variant-annotations]]
        [clojure.math.combinatorics :only [combinations]]
        [clojure.java.io]
        [clojure.string :only [join]])
  (:require [fs.core :as fs]
            [clj-yaml.core :as yaml]
            [bcbio.run.itx :as itx]
            [bcbio.run.broad :as broad]))

;; GATK walker based variance assessment

(defn variant-comparison [sample vcf1 vcf2 ref & {:keys [out-base interval-file]
                                                  :or {out-base nil
                                                       interval-file nil}}]
  "Compare two variant files with GenotypeConcordance in VariantEval"
  (let [file-info {:out-eval (str (itx/file-root (if (nil? out-base) vcf1 out-base)) ".eval")}
        args (concat
              ["-R" ref
               "--out" :out-eval
               "--eval" vcf1
               "--comp" vcf2
               "--sample" sample
               "--evalModule" "GenotypeConcordance"
               "--stratificationModule" "Sample"
               "--stratificationModule" "Filter"]
              (if-not (nil? interval-file) ["-L:bed" interval-file] []))]
    (broad/run-gatk "VariantEval" args file-info {:out [:out-eval]})
    (:out-eval file-info)))

(defn select-by-concordance [sample call1 call2 ref & {:keys [out-dir interval-file]
                                                       :or {out-dir nil
                                                            interval-file nil}}]
  "Variant comparison producing 3 files: concordant and both directions discordant"
  (let [base-dir (if (nil? out-dir) (fs/parent (:file call1)) out-dir)]
    (if-not (fs/exists? base-dir)
      (fs/mkdirs base-dir))
    (doall
     (for [[c1 c2 cmp-type] [[call1 call2 "concordance"]
                             [call1 call2 "discordance"]
                             [call2 call1 "discordance"]]]
       (let [file-info {:out-vcf (str (fs/file base-dir
                                               (format "%s-%s-%s-%s.vcf"
                                                       sample (:name c1) (:name c2) cmp-type)))}
             args (concat
                   ["-R" ref
                    "--sample_name" sample
                    "--variant" (:file c1)
                    (str "--" cmp-type) (:file c2)
                    "--out" :out-vcf]
                   (if-not (nil? interval-file) ["-L:bed" interval-file] []))]
         (broad/run-gatk "SelectVariants" args file-info {:out [:out-vcf]})
         (:out-vcf file-info))))))

;; Custom parsing and combinations using GATK VariantContexts

(defn- vc-by-match-category [in-file]
  "Lazy stream of VariantContexts categorized by concordant/discordant matching."
  (letfn [(genotype-alleles [g]
            (vec (map #(.toString %) (:alleles g))))
          (is-concordant? [vc]
            (= (-> (map genotype-alleles (:genotypes vc))
                   set
                   count)
               1))]
    (for [vc (parse-vcf in-file)]
      [(if (is-concordant? vc) :concordant :discordant)
       vc])))

(defn split-variants-by-match [vcf1 vcf2 ref]
  "Provide concordant and discordant variants for two variant files."
  (let [combo-file (combine-variants [vcf1 vcf2] ref)
        out-map {:concordant (itx/add-file-part combo-file "concordant")
                 :discordant (itx/add-file-part combo-file "discordant")}]
    (if-not (fs/exists? (:concordant out-map))
      (write-vcf-w-template combo-file out-map (vc-by-match-category combo-file)
                            ref))
    out-map))

;; Top-level: process a directory of variant calls from multiple
;; sources, generating a summary of concordance plus detailed metrics
;; differences for tweaking filters.

(defn- get-summary-writer [config config-file ext]
  (if-not (nil? (:outdir config))
    (do
      (if-not (fs/exists? (:outdir config))
        (fs/mkdirs (:outdir config)))
      (writer (str (fs/file (:outdir config)
                            (format "%s-%s"
                                    (itx/file-root (fs/base-name config-file)) ext)))))
    (writer System/out)))

(def top-level-header [:sample :call1 :call2 :genotype_concordance :nonref_discrepency
                       :nonref_sensitivity :concordant :discordant1 :discordant2])

(defn- top-level-metrics [x]
  "Provide one-line summary of similarity metrics for a VCF comparison."
  (letfn [(passes-filter? [vc]
            (= (count (:filters vc)) 0))
           (count-variants [f]
             (count (filter passes-filter? (parse-vcf f))))]
    {:sample (:sample x)
     :call1 (-> x :c1 :name)
     :call2 (-> x :c2 :name)
     :genotype_concordance (-> x :metrics :percent_overall_genotype_concordance)
     :nonref_discrepency (-> x :metrics :percent_non_reference_discrepancy_rate)
     :nonref_sensitivity (-> x :metrics :percent_non_reference_sensitivity)
     :concordant (count-variants (first (:c-files x)))
     :discordant1 (count-variants (second (:c-files x)))
     :discordant2 (count-variants (nth (:c-files x) 2))}))

(defn- prepare-vcf-calls [exp config]
  "Prepare merged and annotated VCF files for an experiment."
  (let [align-bams (map #(get % :align (:align exp)) (:calls exp))
        sample-vcfs (map #(select-by-sample (:sample exp) % (:ref exp)
                                            :out-dir (get config :outdir-prep (:outdir config)))
                         (:calls exp))
        merged-vcfs (create-merged sample-vcfs align-bams (:ref exp)
                                   :out-dir (:outdir config))
        final-vcfs (map (fn [[v b]] (add-variant-annotations v b (:ref exp)))
                        (map vector merged-vcfs align-bams))]
    (map (fn [[c v]] (assoc c :file v))
         (map vector (:calls exp) final-vcfs))))

(defn- compare-two-vcf [c1 c2 exp config]
  "Compare two VCF files based on the supplied configuration."
  (let [c-files (select-by-concordance (:sample exp) c1 c2 (:ref exp)
                                       :out-dir (:outdir config)
                                       :interval-file (:intervals exp))
        eval-file (variant-comparison (:sample exp) (:file c1) (:file c2)
                                      (:ref exp) :out-base (first c-files)
                                      :interval-file (:intervals exp))
        metrics (first (concordance-report-metrics (:sample exp) eval-file))
        out {:c-files c-files :metrics metrics :c1 c1 :c2 c2 :sample (:sample exp)}]
    (assoc out :summary (top-level-metrics out))))

(defn -main [config-file]
  (let [config (-> config-file slurp yaml/parse-string)
        comparisons (flatten
                     (for [exp (:experiments config)]
                       (for [[c1 c2] (combinations (prepare-vcf-calls exp config) 2)]
                         (compare-two-vcf c1 c2 exp config))))]
    (with-open [w (get-summary-writer config config-file "summary.txt")]
      (doseq [x comparisons]
        (.write w (format "* %s : %s vs %s\n" (:sample x)
                          (-> x :c1 :name) (-> x :c2 :name)))
        (doseq [f (:c-files x)]
          (.write w (format "** %s\n" (fs/base-name f)))
          (write-summary-table (vcf-stats f) :wrtr w))
        (write-concordance-metrics (:metrics x) w)))
    (with-open [w (get-summary-writer config config-file "summary.csv")]
      (.write w (format "%s\n" (join "," (map name top-level-header))))
      (doseq [vals (map :summary comparisons)]
        (.write w (format "%s\n" (join "," (map #(% vals) top-level-header))))))))
