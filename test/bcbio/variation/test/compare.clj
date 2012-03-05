(ns bcbio.variation.test.compare
  (:import [org.broadinstitute.sting.utils.exceptions UserException$BadInput])
  (:use [midje.sweet]
        [bcbio.run.itx]
        [bcbio.variation.annotation]
        [bcbio.variation.callable]
        [bcbio.variation.combine]
        [bcbio.variation.compare]
        [bcbio.variation.filter]
        [bcbio.variation.normalize]
        [bcbio.variation.phasing]
        [bcbio.variation.metrics]
        [bcbio.variation.report]
        [bcbio.variation.variantcontext])
  (:require [fs.core :as fs]))

(let [data-dir (str (fs/file "." "test" "data"))
      ref (str (fs/file data-dir "GRCh37.fa"))
      intervals (str (fs/file data-dir "target-regions.bed"))
      vcf1 (str (fs/file data-dir "gatk-calls.vcf"))
      vcf2 (str (fs/file data-dir "freebayes-calls.vcf"))
      align-bam (str (fs/file data-dir "aligned-reads.bam"))
      sample "Test1"
      callable-out (format "%s-callable.bed" (file-root align-bam))
      annotated-out (add-file-part vcf2 "annotated")
      combo-out (add-file-part vcf1 "combine")
      compare-out (str (file-root vcf1) ".eval")
      filter-out (add-file-part vcf1 "filter")
      nofilter-out (add-file-part filter-out "nofilter")
      combine-out [(add-file-part vcf1 "fullcombine-wrefs")
                   (add-file-part vcf2 "fullcombine-wrefs")]
      match-out {:concordant (add-file-part combo-out "concordant")
                 :discordant (add-file-part combo-out "discordant")}
      select-out (doall (map #(str (fs/file data-dir (format "%s-%s.vcf" sample %)))
                             ["gatk-freebayes-concordance"
                              "gatk-freebayes-discordance"
                              "freebayes-gatk-discordance"]))]
  (against-background [(before :facts (vec (map #(if (fs/exists? %)
                                                   (fs/delete %))
                                                (concat
                                                 [combo-out compare-out callable-out
                                                  annotated-out filter-out nofilter-out]
                                                 combine-out
                                                 (vals match-out)
                                                 select-out))))]
    (facts "Variant comparison and assessment with GATK"
      (select-by-concordance sample {:name "gatk" :file vcf1}
                             {:name "freebayes" :file vcf2} ref
                             :interval-file intervals) => select-out
      (combine-variants [vcf1 vcf2] ref) => combo-out
      (variant-comparison sample vcf1 vcf2 ref
                          :interval-file intervals) => compare-out
      (-> (concordance-report-metrics sample compare-out)
          first :percent_non_reference_sensitivity) => "88.89"
      (identify-callable align-bam ref) => callable-out
      (let [[is-callable? call-source] (callable-checker align-bam ref)]
        (with-open [_ call-source]
          (is-callable? "MT" 16 17) => true
          (is-callable? "MT" 252 252) => false
          (is-callable? "MT" 5100 5200) => false
          (is-callable? "MT" 16 15) => false))
      (add-variant-annotations vcf2 align-bam ref) => annotated-out)
    (facts "Create merged VCF files for comparison"
      (create-merged [vcf1 vcf2] [align-bam align-bam] [true true] ref) => combine-out)
    (facts "Filter variant calls avoiding false positives."
      (variant-filter vcf1 ["QD < 2.0" "MQ < 40.0"] ref) => filter-out
      (remove-cur-filters filter-out ref) => nofilter-out
      (split-variants-by-match vcf1 vcf2 ref) => match-out
      (variant-recalibration-filter vcf1 [{:file (:concordant match-out)
                                           :name "concordant"
                                           :prior 10.0}]
                                    ref) => (throws UserException$BadInput
                                    (contains "annotations with zero variance")))))

(facts "Accumulate statistics associated with variations."
  (let [data-dir (str (fs/file "." "test" "data"))
        vcf1 (str (fs/file data-dir "gatk-calls.vcf"))
        vcf2 (str (fs/file data-dir "freebayes-calls.vcf"))]
    (map :metric (vcf-stats vcf1)) => ["AC" "AF" "AN" "BaseQRankSum" "DP" "Dels" "FS"
                                       "HRun" "HaplotypeScore" "MQ" "MQ0" "MQRankSum"
                                       "QD" "QUAL" "ReadPosRankSum"]
    (first (vcf-stats vcf1)) => {:max 2.0, :pct75 2.0, :median 2.0, :pct25 2.0, :min 2.0,
                                 :count 10, :metric "AC"}
    (write-summary-table (vcf-stats vcf1)) => nil
    (let [metrics (get-vcf-classifier-metrics vcf1 vcf2)]
      (count metrics) => 2
      (-> metrics first :cols) => ["AC" "AF" "AN" "DP" "QUAL"]
      (-> metrics second :rows first) => [2.0 1.0 2.0 938.0 99.0]
      (classify-decision-tree metrics) => {:top-metrics #{"DP"}})))

(let [data-dir (str (fs/file "." "test" "data"))
      pvcf (str (fs/file data-dir "phasing-contestant.vcf"))
      ref-vcf (str (fs/file data-dir "phasing-reference.vcf"))]
  (facts "Handle haplotype phasing specified in VCF output files."
    (with-open [pvcf-source (get-vcf-source pvcf)]
      (let [haps (parse-phased-haplotypes pvcf-source)]
        (count haps) => 4
        (count (first haps)) => 5
        (-> haps first first :start) => 10
        (count (second haps)) => 1
        (-> haps (nth 2) first :start) => 16)))
  (facts "Compare phased calls to haploid reference genotypes."
    (with-open [ref-vcf-s (get-vcf-source ref-vcf)
                pvcf-s (get-vcf-source pvcf)]
      (let [cmps (score-phased-calls pvcf-s ref-vcf-s)]
        (map :variant-type (first cmps)) => [:snp :snp :indel :snp :snp]
        (map :comparison (last cmps)) => [:concordant :phasing-error :concordant :discordant]
        (map :nomatch-het-alt (first cmps)) => [true false true false true])))
  (facts "Check is a variant file is a haploid reference."
    (is-haploid? pvcf) => false
    (is-haploid? ref-vcf) => true))

(let [data-dir (str (fs/file "." "test" "data"))
      ref (str (fs/file data-dir "GRCh37.fa"))
      cbed (str (fs/file data-dir "phasing-contestant-regions.bed"))
      rbed (str (fs/file data-dir "phasing-reference-regions.bed"))]
  (facts "Merging and count info for reference and contestant analysis regions."
    (count-comparison-bases rbed cbed ref) => (contains {:compared 12 :total 13})
    (count-comparison-bases rbed nil ref) => (contains {:compared 13 :total 13})))

(facts "Calculate final accuracy score for contestant/reference comparison."
  (calc-accuracy {:total-bases {:compared 10}
                  :discordant {:indel 1 :snp 1}
                  :phasing-error {:indel 1 :snp 1}}) => (roughly 62.50))


(let [data-dir (str (fs/file "." "test" "data"))
      ref (str (fs/file data-dir "GRCh37.fa"))
      vcf (str (fs/file data-dir "cg-normalize.vcf"))
      out-vcf (add-file-part vcf "prep")]
  (against-background [(before :facts (vec (map remove-path [out-vcf])))]
    (facts "Normalize variant representation of chromosomes, order, genotypes and samples."
      (multiple-samples? vcf) => false
      (prep-vcf vcf ref "Test1") => out-vcf)))

(facts "Load configuration files, normalizing input."
  (let [config-file (fs/file "." "config" "method-comparison.yaml")
        config (load-config config-file)]
    (get-in config [:dir :out]) => (has-prefix "/")
    (-> config :experiments first :sample) => "Test1"
    (-> config :experiments first :calls first :file) => (has-prefix "/")
    (-> config :experiments first :calls second :filters first) => "HRun > 5.0"))

(facts "Determine the highest count of items in a list"
  (highest-count []) => nil
  (highest-count ["a" "a"]) => "a"
  (highest-count ["a" "b" "b"]) => "b"
  (highest-count ["a" "a" "b" "b"]) => "a"
  (highest-count ["b" "b" "a" "a"]) => "a")
