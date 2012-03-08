(ns bcbio.variation.variantcontext
  "Helper functions to retrieve information from GATK VariantContext
   objects, which represent variant data stored in VCF files."
  (:import [org.broad.tribble.index IndexFactory]
           [org.broad.tribble.source BasicFeatureSource]
           [org.broadinstitute.sting.utils.codecs.vcf VCFCodec StandardVCFWriter]
           [org.broadinstitute.sting.gatk.refdata.tracks RMDTrackBuilder]
           [org.broadinstitute.sting.gatk.datasources.reference ReferenceDataSource]
           [org.broadinstitute.sting.gatk.arguments ValidationExclusion$TYPE]
           [net.sf.picard.reference ReferenceSequenceFileFactory])
  (:use [clojure.java.io])
  (:require [clojure.string :as string]))

;; ## Represent VariantContext objects
;;
;; Provide simple map-based access to important attributes of
;; VariantContexts. There are 3 useful levels of abstraction:
;;
;;  - VariantContext: Details about a variation. This captures a
;;    single line in a VCF file
;;  - Genotype: An individual genotype for a sample, at a variant position.
;;  - Allele: The actual alleles at a genotype.

(defn from-genotype
  "Represent a sample genotype including alleles.
   :genotype stores the original java genotype object for direct access."
  [g]
  {:sample-name (.getSampleName g)
   :qual (.getPhredScaledQual g)
   :type (-> g .getType .name)
   :attributes (into {} (.getAttributes g))
   :alleles (vec (.getAlleles g))
   :genotype g})

(defn from-vc
  "Provide a top level map of information from a variant context.
   :vc stores the original java VariantContext object for direct access."
  [vc]
  {:chr (.getChr vc)
   :start (.getStart vc)
   :end (.getEnd vc)
   :ref-allele (.getReference vc)
   :alt-alleles (.getAlternateAlleles vc)
   :type (-> vc .getType .name)
   :filters (set (.getFilters vc))
   :attributes (into {} (.getAttributes vc))
   :genotypes (map from-genotype
                   (-> vc .getGenotypes .toArray vec))
   :vc vc})

;; ## Parsing VCF files

(defn get-seq-dict
  "Retrieve Picard sequence dictionary from FASTA reference file."
  [ref-file]
  (ReferenceDataSource. (file ref-file))
  (-> ref-file
      file
      ReferenceSequenceFileFactory/getReferenceSequenceFile
      .getSequenceDictionary))

(defn- vcf-iterator
  "Lazy iterator over variant contexts in the VCF source iterator."
  [iter]
  (lazy-seq
   (if (.hasNext iter)
     (cons (from-vc (.next iter)) (vcf-iterator iter)))))

(defn flexible-vcfcodec []
  "Provide VCFCodec decoder that handles problem VCF files before parsing.
  Fixes:
    - INFO lines with empty attributes (starting with ';'), found in
      Complete Genomics VCF files"
  (letfn [(empty-attribute-info [info]
            (if (.startsWith info ";")
              (subs info 1)
              info))
          (fix-info [xs]
            (assoc xs 7 (-> (nth xs 7)
                            empty-attribute-info)))]
    (proxy [VCFCodec] []
      (decode [line]
        (proxy-super decode (-> line
                                (string/split #"\t")
                                fix-info
                                (#(string/join "\t" %))))))))

(defn get-vcf-source
  "Create a Tribble FeatureSource for VCF file.
   Handles indexing and parsing of VCF into VariantContexts.
   We treat gzipped files as tabix indexed VCFs."
  ([in-file ref-file ensure-safe]
     (if (.endsWith in-file ".gz")
       (BasicFeatureSource/getFeatureSource in-file (VCFCodec.) false)
       (let [validate (when-not ensure-safe
                        ValidationExclusion$TYPE/ALLOW_SEQ_DICT_INCOMPATIBILITY)
             codec (if ensure-safe (VCFCodec.) (flexible-vcfcodec))
             idx (.loadIndex (RMDTrackBuilder. (get-seq-dict ref-file) nil validate)
                             (file in-file) codec)]
         (BasicFeatureSource. (.getAbsolutePath (file in-file)) idx codec))))
  ([in-file ref-file]
     (get-vcf-source in-file ref-file true)))

(defn get-vcf-retriever
  "Indexed VCF file retrieval.
   Returns function that fetches all variants in a region (chromosome:start-end)"
  [vcf-source]
  (fn [chr start end]
    (vcf-iterator (.query vcf-source chr start end))))

(defn parse-vcf
  "Lazy iterator of VariantContext information from VCF file."
  [vcf-source]
  (vcf-iterator (.iterator vcf-source)))

;; ## Writing VCF files

(defmacro with-open-map
  "Emulate with-open using bindings supplied as a map."
  [binding-map & body]
  `(try
     ~@body
     (finally
      (vec (map #(.close %) (vals ~binding-map))))))

(defn write-vcf-w-template
  "Write VCF output files starting with an original input template VCF.
   Handles writing to multiple VCF files simultaneously with the different
   file handles represented as keywords. This allows lazy splitting of VCF files:
   `vc-iter` is a lazy sequence of `(writer-keyword variant-context)`.
   `out-file-map` is a map of writer-keywords to output filenames."
  [tmpl-file out-file-map vc-iter ref & {:keys [header-update-fn]}]
  (letfn [(make-vcf-writer [f ref]
            (StandardVCFWriter. (file f) (get-seq-dict ref)))]
    (with-open [vcf-source (get-vcf-source tmpl-file ref)]
      (let [tmpl-header (.getHeader vcf-source)
            writer-map (zipmap (keys out-file-map)
                               (map #(make-vcf-writer % ref) (vals out-file-map)))]
        (with-open-map writer-map
          (doseq [out-vcf (vals writer-map)]
            (.writeHeader out-vcf (if-not (nil? header-update-fn)
                                    (header-update-fn tmpl-header)
                                    tmpl-header)))
          (doseq [info vc-iter]
            (let [[category vc] (if (and (coll? info) (= 2 (count info)))
                                  info
                                  [:out info])]
              (.add (get writer-map category) vc))))))))
