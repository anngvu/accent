(defproject accent "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/data.csv "1.1.0"]
                 [cheshire "5.13.0"]
                 [org.clojars.huahaiy/datalevin-native "0.9.1"]
                 [org.babashka/http-client "0.4.19"]
                 ]
  :jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
             "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"]
  :main ^:skip-aot accent.chat
  :target-path "target/%s"
  :native-image {:name ""}
  :profiles {:uberjar {:aot :all}})
