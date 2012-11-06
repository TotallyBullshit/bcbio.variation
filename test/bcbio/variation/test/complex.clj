(ns bcbio.variation.test.complex
  "Tests for dealing with more complex variations: structural
  variations and MNPs"
  (:use [midje.sweet]
        [bcbio.variation.combine :only [full-prep-vcf]]
        [bcbio.variation.complex]
        [bcbio.variation.normalize]
        [bcbio.variation.structural]
        [bcbio.variation.variantcontext])
  (:require [fs.core :as fs]
            [bcbio.run.itx :as itx]))

(background
 (around :facts
         (let [data-dir (str (fs/file "." "test" "data"))
               ref (str (fs/file data-dir "GRCh37.fa"))
               target-bed (str (fs/file data-dir "target-regions.bed"))
               sv-vcf1 (str (fs/file data-dir "sv-1000g.vcf"))
               sv-vcf2 (str (fs/file data-dir "sv-illumina.vcf"))
               multi-vcf (str (fs/file data-dir "1000genome-multi.vcf"))
               multi-out (itx/add-file-part multi-vcf "nomnp")
               sv-out {:sv-concordant
                       (str (fs/file data-dir "sv-sv1000g-svIll-svconcordance.vcf"))
                       :sv-sv1000g-discordant
                       (str (fs/file data-dir "sv-sv1000g-svIll-svdiscordance.vcf"))
                       :sv-svIll-discordant
                       (str (fs/file data-dir "sv-svIll-sv1000g-svdiscordance.vcf"))}
               sv-out2
               {:sv-concordant (str (fs/file data-dir "sv-sv1-sv2-svconcordance.vcf"))
                :sv-sv1-discordant (str (fs/file data-dir "sv-sv1-sv2-svdiscordance.vcf"))
                :sv-sv2-discordant (str (fs/file data-dir "sv-sv2-sv1-svdiscordance.vcf"))}
               mnp-vcf (str (fs/file data-dir "freebayes-calls-indels.vcf"))
               cindel-vcf (str (fs/file data-dir "freebayes-calls-complexindels.vcf"))
               cindel-out (itx/add-file-part cindel-vcf "nomnp")
               indel-vcf1 (str (fs/file data-dir "sv-indels-fb.vcf"))
               indel-vcf2 (str (fs/file data-dir "sv-indels-gatk.vcf"))
               indel-out (str (fs/file data-dir "Test-svindfb-svindgatk-svconcordance.vcf"))
               nomnp-out (itx/add-file-part mnp-vcf "nomnp")
               fullprep-out (itx/add-file-part mnp-vcf "fullprep")
               headerfix-out (itx/add-file-part mnp-vcf "samplefix")
               params {:max-indel 100}]
           (doseq [x (concat [nomnp-out indel-out cindel-out headerfix-out fullprep-out
                              multi-out]
                             (vals sv-out) (vals sv-out2))]
             (itx/remove-path x))
           ?form)))

(facts "Deal with multi-nucleotide polymorphisms"
  (normalize-variants mnp-vcf ref) => nomnp-out)

(facts "Split complex indels into individual components"
  (normalize-variants cindel-vcf ref) => cindel-out)

(facts "Parse structural variations"
  (let [vcf-list (parse-vcf-sv sv-vcf2 ref)
        vcf-by-region (parse-vcf-sv sv-vcf1 ref :interval-file target-bed)
        vcf-itree (parse-vcf-sv sv-vcf1 ref :out-format :itree)]
    (-> vcf-list first :start-ci) => 6066065
    (-> vcf-itree (get-itree-overlap "22" 15883520 15883620) first :end-ci) => 15883626
    (count vcf-by-region) => 1
    (with-open [vcf-iter1 (get-vcf-iterator sv-vcf1 ref)
                vcf-iter2 (get-vcf-iterator sv-vcf2 ref)]
      (doall (map #(get-sv-type % params) (parse-vcf vcf-iter1))) =>
      (concat [:INS] (repeat 6 :BND)
              [nil :DEL :INS :DEL :DUP :INV :INS])
      (doall (map #(get-sv-type % params) (parse-vcf vcf-iter2))) =>
      [:DUP :BND :BND :INS :CNV :DEL :INV])))

(facts "Compare structural variation calls from two inputs."
  (compare-sv {:name "sv1000g" :file sv-vcf1}
              {:name "svIll" :file sv-vcf2} ref) => (contains sv-out)
  (compare-sv {:name "sv1" :file sv-vcf1}
              {:name "sv2" :file sv-vcf1} ref) => (contains sv-out2))

(facts "Combine indels from different calling methodologies that overlap."
  (-> (compare-sv {:name "svindfb" :file indel-vcf1} {:name "svindgatk" :file indel-vcf2}
                  ref :params {:max-indel 2 :default-cis [[100 10]]})
      :sv-concordant
      (get-vcf-iterator ref)
      parse-vcf
      count) => 18)

(facts "Full preparation pipeline for variant files"
  (full-prep-vcf mnp-vcf ref) => fullprep-out)

(facts "Fix VCF header problems"
  (fix-vcf-sample mnp-vcf "Test1" ref) => headerfix-out)

(facts "Handle preparation of multi-sample input files"
  (normalize-variants multi-vcf ref) => multi-out)
