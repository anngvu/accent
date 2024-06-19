(ns database.dlvn
  (:gen-class)
  (:require [database.upstream :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [datalevin.core :as d]
            [datalevin.util :as u])
  (:import  [java.util UUID]))


(def db-dir (u/tmp-dir (str "datalevin-" (UUID/randomUUID))))


(def schematic-entity-schema
  ;; :db/ident       :schema/isPartOf
  ;; during import, this is removed because the isPartOf "http://schema.biothings.io"
  ;; is not useful and is technically not true (unless the model has been registered)
  ;; A reference to DCC is much more useful
[{:db/ident       :id
  :db/valueType   :db.type/string
  :db/unique      :db.unique/identity
  :db/doc         "A unique identifier for the entity"}

 {:db/ident       :type
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one
  :db/doc         "Type of the entity"}

 {:db/ident       :rdfs/comment
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one
  :db/fulltext     true
  :db/doc         "Description of the entity"}

 {:db/ident       :rdfs/label
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one
  :db/doc         "Label of the entity"}

 {:db/ident       :rdfs/subClassOf
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/many
  :db/doc         "Reference to parent classes of the entity"}

 {:db/ident       :dcc
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one
  :db/doc         "The Data Coordinating Center that this entity belongs to"}

 {:db/ident       :sms/displayName
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/one
  :db/doc         "Display name of the entity"}

 {:db/ident       :sms/required
  :db/valueType   :db.type/boolean
  :db/cardinality :db.cardinality/one
  :db/doc         "Indicates if the entity is required"}

 {:db/ident       :sms/validationRules
  :db/valueType   :db.type/string
  :db/cardinality :db.cardinality/many
  :db/doc         "Validation rules associated with the entity"}

 {:db/ident       :sms/requiresComponent
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/many
  :db/doc         "Other components associated with entity"}

 {:db/ident       :sms/requiresDependency
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/many
  :db/doc         "Dependencies within the entity"}

 {:db/ident       :schema/rangeIncludes
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/many
  :db/doc         "Enums or valid values included in the range of the entity"}
 ])


(def dlvn-schema
  "Datalevin schema wants structure {:ns/attr {:db/valueType type ...}}"
  (into {} (map (fn [m] {(:db/ident m) (dissoc m :db/ident)}) schematic-entity-schema)))


(defn show-reference-schema
  "Print schema-reference"
   []
   (str schematic-entity-schema))


(defn key-fn [k]
  (->(str/replace k "@" "")
     (str/replace ":" "/")
     (keyword)))


(defn get-schematic-jsonld [path]
  (let [model (json/parse-stream (io/reader path) key-fn)]
    model))


(defn rm-default
  "Remove empty arrays and schema/isPartOf"
  [m]
  (->>(dissoc m :schema/isPartOf)
      (remove #(and (vector? (second %)) (empty? (second %))))
      (into {})))


(defn as-bool [s] (boolean (str/replace s "sms:" "")))


(defn rules-map-to-array [input-map]
  (mapv (fn [[k v]]
          (let [key-str (name k)]
            (str key-str (when (not (empty? v)) (str " " v)))))
        input-map))


(defn transform-rules-conditionally
  "Checks and delegates type of transformation where needed"
  [entity]
  (let [rules (entity :sms/validationRules)]
    (if (map? rules)
      (update entity :sms/validationRules rules-map-to-array)
      entity)))

(defn loadable-graph
  "Transform the JSON-LD graph for loading, ironing out some inconsistencies"
  [graph dcc]
  (->>(map #(assoc % :dcc dcc) graph)
      (map #(update % :sms/required as-bool))
      (map #(transform-rules-conditionally %))
      (map rm-default)))


;; TEST QUERIES
;; TODO move to tests
;; (run-query conn query-required)
;; (d/clear conn)

(def which-required
 '[:find ?e ?displayName
   :where
   [?e :sms/required true]
   [?e :sms/displayName ?displayName]])

(def count-required
'[:find (count ?e)
  :where
  [?e :sms/required true]])

(def count-required-by-dcc
  '[:find ?dcc (count ?e)
    :where
    [?e :dcc ?dcc]
    [?e :sms/required true]
    :group-by ?dcc])

(def unique-dccs
  '[:find ?dcc
    :where
   [?e :dcc ?dcc]])

(def largest-dcc-model
  '[:find ?dcc (count ?e)
    :where
    [?e :dcc ?dcc]
    :group-by ?dcc
    :order (desc (count ?e))
    :limit 1])

(def scrnaseq-template
  '[:find ?attr ?val
    :where
    [?e :rdfs/label "ScRNA-seqLevel1"]
    [?e ?attr ?val]])

(def required-by-dcc
  "TODO: debug and simplify"
  '[:find ?dcc (count ?e-required) (count ?e-not-required)
    :where
    [?e :dcc ?dcc]
    (or
     [?e :sms/required true]
     [?e :sms/required false])
    [(ground true) ?true]
    [(ground false) ?false]
    (or
     [(and [?e :sms/required true] [?e ?e-required])]
     [(and [?e :sms/required false] [?e ?e-not-required])])
    :group-by ?dcc])


(def find-self-dep
  '[:find ?label
    :where
    [?e :sms/requiresDependency ?e]
    [?e :rdfs/label ?label]])


(def by-id
  '[:find ?attr ?val
    :in $ ?entityId
    :where
    [?e :id ?entityId]
    [?e ?attr ?val]])


(def ratio-required
  "TODO: debug and simplify"
 '[:find ?dcc ?required-count ?not-required-count (/ ?required-count ?not-required-count)
   :where
   [?e :dcc ?dcc]
   [?e :sms/required true]])


(def find-dataset-schema
  "Find the dataset schema in the model"
  '[])


;; RULES
;; TODO translate schematic rules to attribute predicates;
;; Then add fun to transform graph data to install attribute preds

(defn get-model-url [config]
  (get-in config [:config :dcc :data_model_url]))


(defn graph-from-url
  [url]
  (->>(read-json url key-fn)
      (:graph)))



(defn transform-dca-config [config]
  {:dcc (get-in config [:config :dcc :name])
   :data_model_url (get-model-url config)})


(defn namespaced-graph [config] (loadable-graph (config :graph) (config :dcc)))

(defn get-model-graphs
  "Pipeline to process a DCA config, prep and import model graphs.
  Ones unable to be retrieved are nil and removed"
  [configs]
  (->>(map transform-dca-config configs)
      (map #(assoc % :graph (graph-from-url (% :data_model_url))))
      (remove #(nil? (% :graph)))
      (mapv namespaced-graph)))


(defn load-graphs!
  "Load a collection of graphs, skips problematic graphs."
  [conn graphs]
  (doseq [[idx g] (map-indexed vector graphs)]
    (try
      (d/transact! conn g)
      (catch Exception e
        (println "Failed to transact graph at index:" idx)
        (println "Error:" (.getMessage e))))))


(defn load-graph-incrementally!
  "Load a graph entity-by-entity; method is mainly to check entities that contain issues."
  [conn graph]
  (doseq [entity graph]
    (try
      (d/transact! conn [entity])
      (catch Exception e
        (println "Failed at entity:" (entity :rdfs/label))
        (println "Error:" (.getMessage e))))))

(defn run-query
  "Send query to connection conn"
  ([conn q]
   (d/q q (d/db conn)))
  ([conn q variable]
   (d/q q (d/db conn) variable)))


(defn ask-database
  [conn query-string]
  (let [q (read-string query-string)
        answer (run-query conn q)]
    (println-str answer)))


(defn write-json-file [data file-path]
  (with-open [writer (io/writer file-path)]
    (json/generate-stream data writer)))


;; STATS
;;
(defn graph-stats
  "Stats for graphs inserted into db. TODO: implement more stats."
  [graphs]
  (count graphs))

;; OPERATIONS

(def conn (d/get-conn db-dir dlvn-schema))
(def dcc-configs (get-dcc-configs))
(def graphs (get-model-graphs dcc-configs))
;(def htan (graphs 5))
;(def demo (graphs 0))
; (d/transact! conn demo)
(load-graphs! conn graphs)
;


;; Save fallback DCC configs
;;(write-json-file dcc-configs "configs.json")
