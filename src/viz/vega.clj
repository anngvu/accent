(ns viz.vega
  (:gen-class)
  (:require [clojure.java.browse :refer [browse-url]]))


(defn as-vega-spec [data encoding]
  {"$schema" "https://vega.github.io/schema/vega-lite/v5.json"
   :data data
   :encoding encoding})

(defn export-plot
  "Export plot as static HTML"
  [vega-spec-data filename]
  (do
    (spit vega-spec-data filename)))

(defn plot-and-show [p filename]
  (do
    (export-plot p filename)
    (browse-url filename)))
