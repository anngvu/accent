(ns database.upstream
  (:gen-class)
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]))


(defn read-json
  "TODO: Additional validation spec for json being read"
  [path & [keyfn]]
  (try
    (json/parse-stream (io/reader path) (or keyfn true))
    (catch com.fasterxml.jackson.core.JsonParseException _ (println "Error: Invalid JSON data"))
    (catch Exception e
      (str "Error: " (.getMessage e)))
    (finally nil)))


(defn get-dca-configs
  "Load configs from the DCA config repo, base url can specify a specific branch"
  [& {:keys [url] :or {url "https://raw.githubusercontent.com/Sage-Bionetworks/data_curator_config/prod/"}}]
  (let [tenants (str url "tenants.json")]
    (println "Getting from" url)
    (some->>(read-json tenants)
            (:tenants)
            (mapv (fn [m] {:name (:name m)
                           :config (read-json (str url (:config_location m)))})))))


(defn read-local-configs
  "Read configs from a local configs.json file"
  []
  (read-json "configs.json"))


(defn get-dcc-configs
  "Get configs from a config store, currently defaults to DCA repo,
  with fallback to local configs.json, though there is possibility for other config store type."
  [& {:keys [url]}]
  (try
    (get-dca-configs {:url url})
    (catch Exception _
      (println "Error fetching latest DCC config data, falling back to local data which may be out of date.")
      (read-local-configs))))


;(def dcc-configs (get-dcc-configs))
;(def all (get-model-graphs dca-cfgs))
