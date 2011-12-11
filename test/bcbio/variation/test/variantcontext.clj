(ns bcbio.variation.test.variantcontext
  (:use [midje.sweet]
        [bcbio.variation.variantcontext])
  (:require [fs]))

(let [data-dir (fs/join (fs/cwd) "test" "data")
      vcf-file (fs/join data-dir "gatk-calls.vcf")
      vc (first (parse-vcf vcf-file))]
  (facts "Parsing VCF file to VariantContext"
    (:chr vc) => "chrM"
    (:start vc) => 73
    (:filters vc) => #{}
    (get (:attributes vc) "DP") => "250"
    (-> vc :genotypes count) => 1
    (-> vc :genotypes first :sample-name) => "Test1"
    (-> vc :genotypes first :attributes) => {"PL" "5820,645,0", "AD" "0,250", "DP" "250"}))
