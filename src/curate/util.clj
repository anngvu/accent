(ns curate.util
  (:gen-class)
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            ))

;;;;;;;;;;;;;;;;;;;;;;;
;; Defs
;; ;;;;;;;;;;;;;;;;;;;;


;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn as-numeric [s]
  (try
    (Double/parseDouble s)
    (catch Exception _ nil)))

(defn s-quote [s] (str "'" s "'"))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn read-csv-file [file-path]
  (with-open [reader (io/reader file-path)]
    (doall
     (csv/read-csv reader))))

(defn fill-val
  "Look up val for k in summary ref"
  [k ref]
  (if-let [val (get-in ref [k :unique-values])]
    val
    "TBD"))

(defn summarize-column [column-data]
  (let [data-na-removed (remove str/blank? column-data)
        nums (map as-numeric data-na-removed)]
    (if (and (seq nums) (every? number? nums))
      {:type "numeric" :min (apply min nums) :max (apply max nums)}
      {:type "ordinal" :first-20-unique-values (take 20 (distinct column-data)) :total-unique-values (count (distinct column-data))})))

(defn summarize-manifest
  "Read a csv manifest, analyze and summarize it column-by-column."
  [file-path]
  (let [manf (read-csv-file file-path)
        headers (first manf)
        columns (apply map vector (rest manf))]
    (zipmap headers (map summarize-column columns))))

(defn derive-from-manifest
  "Derive metadata from manifest metadata"
  [file-path dataset-props]
  (->(summarize-manifest file-path)
     (select-keys dataset-props)
     (update-vals :unique-values)))
