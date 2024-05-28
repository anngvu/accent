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


(defn get-from-config-store
  "Load configs from the config store, i.e. the DCA config repo"
  []
  (let [rel "https://raw.githubusercontent.com/Sage-Bionetworks/data_curator_config/prod/"
        tenants (str rel "tenants.json")]
    (some->>(read-json tenants)
            (:tenants)
            (mapv (fn [m] {:name (:name m)
                           :config (read-json (str rel (:config_location m)))})))))


(defn read-local-configs
  "Read configs from a local configs.json file"
  []
  (read-json "configs.json"))


(defn get-configs
  "Get configs from the config store, fallback to local configs.json if fail"
  []
  (try
    (get-from-config-store)
    (catch Exception e
      (println "Error fetching latest DCC config data, falling back to local data which may be out of date.")
      (read-local-configs))))


;(def dcc-configs (get-from-config-store))
;(def all (get-model-graphs dca-cfgs))
