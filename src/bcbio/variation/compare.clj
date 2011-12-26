;; Generate comparisons between two sets of variant calls
;; Utilizes GATK walkers to generate detailed and summary statistics
;; about two sets of calls
;; - Identify non-callable regions with CallableLociWalker
;; - Combine variants from two samples
;; - Use VariantEval to calculate overall concordance statistics
;; - Provide output for concordant and discordant regions for
;;   detailed investigation

(ns bcbio.variation.compare
  (:import [org.broadinstitute.sting.gatk CommandLineGATK])
  (:require [fs.core :as fs]))

(defn file-root [fname]
  "Retrieve file name without extension: /path/to/fname.txt -> /path/to/fname"
  (let [i (.lastIndexOf fname ".")]
    (if (pos? i)
      (subs fname 0 i)
      fname)))

(defn add-file-part [fname part]
  "Add file extender: base.txt -> base-part.txt"
  (format "%s-%s%s" (file-root fname) part (fs/extension fname)))

(defn- run-gatk [program args]
  (let [std-args ["-T" program "--phone_home" "NO_ET"]]
    (CommandLineGATK/start (CommandLineGATK.)
                           (into-array (concat std-args args)))))

(defn combine-variants [vcf1 vcf2 ref]
  "Combine two variant files with GATK CombineVariants."
  (letfn [(unique-name [f]
            (-> f fs/base-name file-root))]
    (let [out-file (add-file-part vcf1 "combine")
          args ["-R" ref
                (str "--variant:" (unique-name vcf1)) vcf1
                (str "--variant:" (unique-name vcf2)) vcf2
                "-o" out-file
                "--genotypemergeoption" "UNIQUIFY"]]
      (if-not (fs/exists? out-file)
        (run-gatk "CombineVariants" args))
      out-file)))

(defn variant-comparison [vcf1 vcf2 ref]
  "Compare two variant files with GenotypeConcordance in VariantEval"
  (let [out-file (str (file-root vcf1) ".eval")
        args ["-R" ref
              "--out" out-file
              "--eval" vcf1
              "--comp" vcf2
              "--evalModule" "GenotypeConcordance"
              "--stratificationModule" "Sample"]]
      (if-not (fs/exists? out-file)
        (run-gatk "VariantEval" args))
      out-file))

(defn -main [vcf1 vcf2 bam ref])
