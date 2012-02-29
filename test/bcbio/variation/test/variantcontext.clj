(ns bcbio.variation.test.variantcontext
  (:use [midje.sweet]
        [bcbio.variation.variantcontext])
  (:require [fs.core :as fs]))

(let [data-dir (str (fs/file "." "test" "data"))
      vcf-file (str (fs/file data-dir "gatk-calls.vcf"))]
  (facts "Parsing VCF file to VariantContext"
    (with-open [vcf-source (get-vcf-source vcf-file)]
      (let [vc (first (parse-vcf vcf-source))]
        (:chr vc) => "MT"
        (:start vc) => 73
        (:type vc) => "SNP"
        (:filters vc) => #{}
        (get (:attributes vc) "DP") => "250"
        (-> vc :genotypes count) => 1
        (-> vc :genotypes first :qual) => 99.0
        (-> vc :genotypes first :type) => "HOM_VAR"
        (-> vc :genotypes first :sample-name) => "Test1"
        (-> vc :genotypes first :attributes) => {"PL" "5820,645,0", "AD" "0,250", "DP" "250"}))))
