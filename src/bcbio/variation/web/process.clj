(ns bcbio.variation.web.process
  "Run scoring analysis, handling preparation of input files and run configuration."
  (:import [java.util.UUID])
  (:use [clojure.java.io]
        [ring.util.response :only (response content-type)]
        [bcbio.variation.compare :only (variant-comparison-from-config)]
        [bcbio.variation.report :only (prep-scoring-table)])
  (:require [fs.core :as fs]
            [clj-yaml.core :as yaml]
            [net.cgrand.enlive-html :as html]
            [doric.core :as doric]))

;; ## Run scoring based on inputs from web or API

(defn create-work-config
  "Create configuration for processing inputs using references supplied in config."
  [in-files work-info config]
  (if-not (fs/exists? (:dir work-info))
    (fs/mkdirs (:dir work-info)))
  (let [config-file (str (fs/file (:dir work-info) "process.yaml"))]
    (->> {:dir {:out (str (fs/file (:dir work-info) "grading"))
                :prep (str (fs/file (:dir work-info) "grading" "prep"))}
          :experiments [{:sample (-> config :ref :sample)
                         :ref (-> config :ref :genome)
                         :intervals (-> config :ref :intervals)
                         :calls [{:name "reference"
                                  :file (-> config :ref :variants)}
                                 {:name "contestant"
                                  :prep true
                                  :file (if-let [x (:variant-file in-files)]
                                          x
                                          (-> config :ref :default-compare))
                                  :intervals (if-let [x (:region-file in-files)]
                                               x
                                               (-> config :ref :intervals))}]}]}
         yaml/generate-string
         (spit config-file))
    config-file))

(defn html-summary-table
  "Generate a summary table of scoring results."
  [comparisons]
  {:pre [(= 1 (count comparisons))]}
  (let [scoring-table (prep-scoring-table (-> comparisons first :metrics))]
    (-> (str (doric/table ^{:format doric/html} [:metric :value] scoring-table))
     java.io.StringReader.
     html/html-resource
     (html/transform [:table] (html/set-attr :class "table table-condensed")))))

(defn html-scoring-summary
  "Generate summary of scoring results for display."
  [request comparisons]
  (let [template-dir (-> request :config :dir :template)
        sum-table (html-summary-table comparisons)]
    (html/transform (html/html-resource (fs/file template-dir "scoresum.html"))
                    [:div#score-table]
                    (html/content sum-table))))

(defn scoring-html
  "Update main page HTML with content for scoring."
  [request]
  (let [html-dir (-> request :config :dir :html-root)
        template-dir (-> request :config :dir :template)]
    (apply str (html/emit*
                (html/transform (html/html-resource (fs/file html-dir "index.html"))
                                [:div#main-content]
                                (-> (fs/file template-dir "score.html")
                                    html/html-resource
                                    (html/select [:body])
                                    first
                                    html/content))))))

(defn run-scoring
  "Run scoring analysis from details provided in current session."
  [request]
  (let [process-config (create-work-config (-> request :session :in-files)
                                           (-> request :session :work-info)
                                           (:config request))
        comparisons (variant-comparison-from-config process-config)]
    (apply str (html/emit*
                (html-scoring-summary request comparisons)))))

(defn prep-scoring
  "Download form-supplied input files and prep directory for scoring analysis."
  [request]
  (letfn [(prep-tmp-dir [request]
            (let [tmp-dir (-> request :config :dir :work)
                  work-id (str (java.util.UUID/randomUUID))
                  cur-dir (fs/file tmp-dir work-id)]
              (fs/mkdirs cur-dir)
              {:id work-id :dir (str cur-dir)}))
          (download-file [tmp-dir request name]
            (let [cur-param (-> request :params (get name))
                  out-file (fs/file tmp-dir (:filename cur-param))]
              [(keyword name) (if (> (:size cur-param) 0)
                                (do
                                  (copy (:tempfile cur-param) out-file)
                                  (str out-file)))]))]
    (let [work-info (prep-tmp-dir request)
          in-files (into {} (map (partial download-file (:dir work-info) request)
                                 ["variant-file" "region-file"]))]
      (-> (response (scoring-html request))
          (content-type "text/html")
          (assoc :session
            (-> (:session request)
                (assoc :work-info work-info)
                (assoc :in-files in-files)))))))

;; ## File retrieval from processing

(defn get-variant-file
  "Retrieve processed output file for web display."
  [request]
  (letfn [(sample-file [ext]
            (str (-> request :config :ref :sample) ext))]
    (let [file-map {"concordant" (sample-file "-contestant-concordant.vcf")
                    "discordant" (sample-file "-contestant-discordant.vcf")
                    "phasing" (sample-file "-contestant-phasing-error.vcf")}
          base-dir (-> request :session :work-info :dir)
          work-dir (if-not (nil? base-dir) (fs/file base-dir "grading"))
          name (get file-map (-> request :params :name))
          fname (if-not (or (nil? work-dir)
                            (nil? name)) (str (fs/file work-dir name)))]
      (if (and (not (nil? fname)) (fs/exists? fname))
        (-> (response (slurp fname))
            (content-type "text/plain"))
        "Variant file not found"))))
