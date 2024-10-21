(defproject accent "0.2.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :repositories [["sagebionetworks-artifactory" "https://sagebionetworks.jfrog.io/artifactory/libs-releases-local/"]]
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/data.csv "1.1.0"]
                 [cheshire "5.13.0"]
                 [org.clojars.huahaiy/datalevin-native "0.9.1"]
                 [org.babashka/http-client "0.4.19"]
                 [org.sagebionetworks/synapseJavaClient "500.0"]
                 [http-kit "2.5.3"]
                 [compojure "1.6.2"]
                 [ring/ring-core "1.9.4"]
                 [com.brunobonacci/mulog "0.9.0"]
                 [hiccup "2.0.0-RC3"]
                 [org.apache.tika/tika-core "2.8.0"]
                 [org.apache.tika/tika-parsers-standard-package "2.8.0"]]
  :jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
             "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"]
  :main ^:skip-aot accent.chat
  :target-path "target/%s"
  :native-image {:name ""}
  :profiles {:uberjar {:aot :all}})
