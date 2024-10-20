(ns viz.vega
  (:gen-class)
  (:require [hiccup2.core :as h]
            [clojure.java.browse :refer [browse-url]]))


(defn as-vega-spec [{:keys [data mark encoding]}]
  {"$schema" "https://vega.github.io/schema/vega-lite/v5.json"
   :data data
   :mark mark
   :encoding encoding})


(defn generate-html
  [vega-spec]
  [:html
   [:head
    [:script {:src "https://cdn.jsdelivr.net/npm/vega@5.29.0"}]
    [:script {:src "https://cdn.jsdelivr.net/npm/vega-lite@5.18.1"}]
    [:script {:src "https://cdn.jsdelivr.net/npm/vega-embed@6.25.0"}]]
   [:body
    [:div#vis]
    [:script
     (str "const spec = "
          (pr-str vega-spec)
          "; vegaEmbed(\"#vis\", spec, {mode: \"vega-lite\"}).then(console.log).catch(console.warn);")]]])


(defn export-plot
  "Export plot as static HTML"
  [filename html] ((spit filename html)))


(defn plot-and-show 
  []
  (let [filename "plot.html"])
  (->>(as-vega-spec)
   (generate-html)
   (spit filename)
    (browse-url file)))
