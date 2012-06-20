(ns bcbio.align.ref
  "Deal with reference sequences for alignment and variant calling."
  (:import [org.broadinstitute.sting.gatk.datasources.reference ReferenceDataSource]
           [net.sf.picard.reference ReferenceSequenceFileFactory])
  (:use [clojure.java.io]
        [ordered.map :only [ordered-map]])
  (:require [clojure.string :as string]
            [bcbio.run.itx :as itx]))

(defn get-seq-dict
  "Retrieve Picard sequence dictionary from FASTA reference file."
  [ref-file]
  (ReferenceDataSource. (file ref-file))
  (-> ref-file
      file
      ReferenceSequenceFileFactory/getReferenceSequenceFile
      .getSequenceDictionary))

(defn get-seq-name-map
  "Retrieve map of sequence names to index positions in the input reference.
   This is useful for sorting by position."
  [ref-file]
  (reduce (fn [coll [i x]] (assoc coll x i))
          (ordered-map)
          (map-indexed vector
                       (map #(.getSequenceName %) (.getSequences (get-seq-dict ref-file))))))

(defn sort-bed-file
  "Sort a BED file relative to the input reference"
  [bed-file ref-file]
  (letfn [(process-line [line]
            (let [parts (if (> (count (string/split line #"\t")) 1)
                          (string/split line #"\t")
                          (string/split line #" "))]
              (let [[chr start end] (take 3 parts)]
                [[chr (Integer/parseInt start) (Integer/parseInt end)] line])))
          (ref-sort-fn [ref-file]
            (let [contig-map (get-seq-name-map ref-file)]
              (fn [x]
                (let [sort-vals (first x)]
                  (vec (cons (get contig-map (first sort-vals))
                             (rest sort-vals)))))))]
    (let [out-file (itx/add-file-part bed-file "sorted")]
      (when (itx/needs-run? out-file)
        (with-open [rdr (reader bed-file)
                    wtr (writer out-file)]
          (doseq [[_ line] (sort-by (ref-sort-fn ref-file)
                                    (map process-line (line-seq rdr)))]
            (.write wtr (str line "\n")))))
      out-file)))

