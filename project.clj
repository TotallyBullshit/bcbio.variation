(defproject bcbio.variation "0.0.1"
  :description "Clojure API for variation data, built on GATK"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/math.combinatorics "0.0.2" :exclusions [org.clojure/clojure]]
                 [org.clojure/data.csv "0.1.2" :exclusions [org.clojure/clojure]]
                 [org.clojure/core.match "0.2.0-alpha9"]
                 ;; GATK requirements
                 [org.clojars.chapmanb/gatk-lite "2.1.8"]
                 [org.clojars.chapmanb/picard "1.73"]
                 [org.clojars.chapmanb/sam "1.73"]
                 [org.clojars.chapmanb/tribble "110"]
                 [org.clojars.chapmanb/cofoja "1.0-20110609"]
                 [org.clojars.chapmanb/jama "1.0.2"]
                 [org.apache.commons/commons-jexl "2.1.1"]
                 [org.apache.commons/commons-math "2.2"]
                 [org.reflections/reflections "0.9.5-RC2"]
                 [org.simpleframework/simple-xml "2.0.4"]
                 [colt/colt "1.2.0"]
                 ;;
                 [org.biojava/biojava3-core "3.0.4"]
                 [org.biojava/biojava3-alignment "3.0.4"]
                 [org.clojars.chapmanb/circdesigna "0.0.2"]
                 [clj-genomespace "0.1.3"]
                 [clj-blend "0.1.0"]
                 [incanter/incanter-core "1.3.0" :exclusions [org.clojure/clojure]]
                 [incanter/incanter-charts "1.3.0" :exclusions [org.clojure/clojure]]
                 [nz.ac.waikato.cms.weka/weka-stable "3.6.6"]
                 [org.clojars.chapmanb/fast-random-forest "0.98"]
                 [com.leadtune/clj-ml "0.2.4" :exclusions [cs.waikato.ac.nz/weka
                                                           hr.irb/fastRandomForest
                                                           org.clojure/clojure
                                                           incanter/incanter-core
                                                           incanter/incanter-charts]]
                 [fs "1.1.2" :exclusions [org.clojure/clojure]]
                 [clj-yaml "0.3.1"]
                 [doric "0.7.0" :exclusions [org.clojure/clojure]]
                 [ordered "1.3.2" :exclusions [org.clojure/clojure]]
                 [de.kotka/lazymap "3.0.0"]
                 [pallet-fsm "0.1.0"]
                 [clj-time "0.4.3"]
                 [clj-aws-s3 "0.3.1" :exclusions [org.codehaus.jackson/jackson-mapper-asl
                                                  org.codehaus.jackson/jackson-core-asl]]
                 [org.clojure/java.jdbc "0.2.2"]
                 [org.xerial/sqlite-jdbc "3.7.2"]
                 [c3p0/c3p0 "0.9.1.2"]
                 [compojure "1.1.3"]
                 [ring/ring-core "1.1.5"]
                 [ring/ring-jetty-adapter "1.1.5"]
                 [shoreleave/shoreleave-remote "0.2.2"]
                 [com.cemerick/shoreleave-remote-ring "0.0.2"]
                 [ring-anti-forgery "0.2.0"]
                 [enlive "1.0.1" :exclusions [org.clojure/clojure]]
                 [hiccup "1.0.1"]
                 [crate "0.2.0-alpha4" :exclusions [org.clojure/clojurescript]]
                 [domina "1.0.0" :exclusions [org.clojure/clojurescript]]
                 [jayq "0.1.0-alpha4"]
                 [com.keminglabs/chosen "0.1.6"]]
  :plugins [[lein-cljsbuild "0.2.7"]
            [lein-marginalia "0.7.1"]
            [lein-midje "2.0.0-SNAPSHOT"]
            [lein-ring "0.7.5"]]
  :profiles {:dev {:dependencies
                   [[midje "1.4.0" :exclusions [org.clojure/clojure ordered]]]}
             :cljs {:dependencies [[org.reflections/reflections "0.9.5-RC2"
                                    :exclusions [com.google.collections/google-collections]]]}}
  :repositories {"biojava" {:url "http://www.biojava.org/download/maven/"
                            :snapshots false}}
  :java-source-paths ["src/java"]
  :jvm-opts ["-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.StdErrLog"]
  ;:jvm-opts ["-Xmx4g"]
  :omit-source false
  :aot [bcbio.variation.vcfwalker bcbio.variation.core bcbio.variation.annotate.nbq
        bcbio.variation.annotate.entropy bcbio.variation.annotate.mfe]
  :main bcbio.variation.core
  :aliases {"variant-compare" ["run" "-m" "bcbio.variation.compare"]
            "variant-web" ["run" "-m" "bcbio.variation.web.server"]
            "variant-prep" ["run" "-m" "bcbio.variation.combine"]
            "variant-evaluate" ["run" "-m" "bcbio.variation.evaluate"]
            "variant-haploid" ["run" "-m" "bcbio.variation.haploid"]
            "variant-recall" ["run" "-m" "bcbio.variation.recall"]
            "variant-reorder" ["run" "-m" "bcbio.align.reorder"]
            "variant-utils" ["run" "-m" "bcbio.variation.utils.core"]
            "variant-custom" ["run" "-m" "bcbio.variation.custom.core"]}
  :ring {:handler bcbio.variation.web.server/main-handler
         :init bcbio.variation.web.server/default-config}
  :cljsbuild {:builds
              [{:source-path "src/cljs"
                :compiler {:output-to "public/js/score.js"
                           :optimizations :advanced
                           :pretty-print false
                           ;;:optimizations :whitespace
                           ;;:pretty-print true
                           :externs ["externs/jquery.js"]}}]})
