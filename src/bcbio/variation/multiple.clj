(ns bcbio.variation.multiple
  "Handle useful comparisons from multiple variation calling approaches.
  High level API to consolidate pairwise variant comparisons."
  (:use [ordered.map :only [ordered-map]]
        [bcbio.variation.combine :only [combine-variants]]
        [bcbio.variation.metrics :only [nonref-passes-filter?]]
        [bcbio.variation.variantcontext :only [parse-vcf get-vcf-retriever
                                               get-vcf-source write-vcf-w-template]])
  (:require [clojure.string :as string]
            [fs.core :as fs]
            [bcbio.run.itx :as itx]
            [bcbio.run.broad :as broad]))

(defn prep-cmp-name-lookup
  "Lookup map of comparisons by method names."
  [cmps & {:keys [ignore] :or {ignore #{}}}]
  (reduce (fn [m x]
            (let [names [(-> x :c1 :name) (-> x :c2 :name)]]
              (if (some #(contains? ignore %) names) m
                  (assoc m names x))))
          (ordered-map)
          cmps))

(defn- select-variant-by-set
  "Select samples based on name of a 'set' from CombineVariants."
  [vcf-in ref set-name & {:keys [out-dir]}]
  (let [file-info {:out-vcf (itx/add-file-part vcf-in set-name out-dir)}
        args ["-R" ref
              "-o" :out-vcf
              "--variant" vcf-in
              "-select" (format "set == '%s'" set-name)]]
    (broad/run-gatk "SelectVariants" args file-info {:out [:out-vcf]})
    (:out-vcf file-info)))

(defn- gen-all-concordant
  "Create VCF of the intersection of all concordant calls."
  [cmps-by-name out-dir config & {:keys [do-include? base-ext]
                                  :or {base-ext "multiall"}}]
  (let [concordant-map (reduce (fn [m [k v]]
                                 (if (or (nil? do-include?) (do-include? k))
                                   (assoc m (-> v :c-files (get "concordant")) (string/join "-" k))
                                   m))
                               (ordered-map) cmps-by-name)
        ref (-> cmps-by-name vals first :exp :ref)
        out-dir (str (fs/file (get-in config [:dir :prep] (get-in config [:dir :out]))
                              "multiple"))
        union-vcf (combine-variants (keys concordant-map) ref :merge-type :full :out-dir out-dir
                                    :name-map concordant-map :base-ext base-ext)]
    {:union union-vcf
     :intersection (select-variant-by-set union-vcf ref "Intersection")}))

(defn- gen-target-fps
  "Generate false positives: discordant calls also called in other samples."
  [target-cmps target-name other-conc-vcf ref out-dir]
  (letfn [(check-shared [fetch]
            (fn [x]
              (and (not (empty? (fetch (:chr x) (:start x) (:end x))))
                   (nonref-passes-filter? x))))
          (get-shared-discordant [xs fetch]
            (let [pass-and-shared? (check-shared fetch)]
              (map :vc (filter pass-and-shared? xs))))]
    (let [disc-vcfs (map (fn [v] (get (:c-files v) (format "%s-discordant" target-name)))
                         (vals target-cmps))
          disc-vcf (-> (combine-variants disc-vcfs ref :merge-type :full :out-dir out-dir
                                         :base-ext (format "dis%s" target-name))
                       (select-variant-by-set ref "Intersection"))
          out-file (itx/add-file-part disc-vcf "shared")]
      (with-open [disc-source (get-vcf-source disc-vcf ref)
                  other-source (get-vcf-source other-conc-vcf ref)]
        (let [vrn-fetch (get-vcf-retriever other-source)]
          (write-vcf-w-template disc-vcf {:out out-file}
                                (get-shared-discordant (parse-vcf disc-source) vrn-fetch)
                                ref)))
      out-file)))

(defn- gen-target-problems
  "Create files of false negatives and positives from target-name."
  [target-name cmps-by-name true-p-vcf out-dir config]
  (letfn [(not-target? [x]
            (not (contains? (set x) target-name)))]
    (let [ref (-> cmps-by-name vals first :exp :ref)
          notarget-concordant (gen-all-concordant cmps-by-name out-dir config
                                                  :do-include? not-target?
                                                  :base-ext (format "multino%s" target-name))]
      {:false-negatives
       (-> (combine-variants [true-p-vcf (:intersection notarget-concordant)]
                             ref :merge-type :full :out-dir out-dir
                             :name-map {true-p-vcf "truep"
                                        (:intersection notarget-concordant) target-name}
                             :base-ext (format "multiall-no%s" target-name))
           (select-variant-by-set ref target-name))
       :false-positives (gen-target-fps (remove #(not-target? (first %))
                                                cmps-by-name)
                                        target-name (:union notarget-concordant)
                                        ref out-dir)})))

(defn multiple-overlap-analysis
  "Provide high level concordance overlap comparisons for multiple call approaches.
  Organizes relative to the given target name generating:
   - VCF of calls concordant in all methods: intersection of all concordant calls.
     These are true positives.
   - VCF of calls discordant in the target method, but concordant in the remainder:
     the intersection of all concordant pairs not including target-name minus the
     overall intersection of concordants. These are false negatives.
   - VCF of non-ref calls discordant in the target method and called in any of the other
     methods. We restrict to shared calls to avoid penalizing unique calls.
     These are false positives."
  [cmps config target-name]
  (let [cmps-by-name (if-not (map? cmps) (prep-cmp-name-lookup cmps :ignore #{"all"}) cmps)
        out-dir (str (fs/file (get-in config [:dir :prep] (get-in config [:dir :out]))
                              "multiple"))]
    (when-not (fs/exists? out-dir)
      (fs/mkdirs out-dir))
    (let [true-p-vcf (:intersection (gen-all-concordant cmps-by-name out-dir config))
          target-problems (gen-target-problems target-name cmps-by-name true-p-vcf
                                               out-dir config)]
      (ordered-map :true-positives true-p-vcf
                   :false-negatives (:false-negatives target-problems)
                   :false-positives (:false-positives target-problems)))))

(defn pipeline-compare-multiple
  "Perform high level pipeline comparison of a target with multiple experiments."
  [cmps finalizer exp config]
  (let [analysis (multiple-overlap-analysis cmps config (:target finalizer))]
    {:c-files analysis
     :c1 {:name (:target finalizer)}
     :c2 {:name "all"}
     :exp exp :dir (config :dir)}))
