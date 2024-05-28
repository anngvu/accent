(ns database.dlvn
  (:gen-class)
  (:require [database.upstream :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [datalevin.core :as d]))


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
  :db/doc         "Descriptive comment about the entity"}

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

 {:db/ident       :sms/requiresDependency
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/many
  :db/doc         "Components associated with the entity"}

 {:db/ident       :schema/rangeIncludes
  :db/valueType   :db.type/ref
  :db/cardinality :db.cardinality/many
  :db/doc         "Enums or valid values included in the range of the entity"}
 ])


(def dlvn-schema
  (into {} (map (fn [m] {(:db/ident m) (dissoc m :db/ident)}) schematic-entity-schema)))


(def schema-reference
  "More entities may be added"
  schematic-entity-schema)


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


(defn good-graph
  "Prep the JSON-LD graph for loading"
  [jsonld dcc]
  (->>(jsonld :graph)
      (map #(assoc % :dcc dcc))
      (map #(update % :sms/required as-bool))
      (map rm-default)))


(def query-required
 '[:find ?e ?displayName
   :where
   [?e :sms/required true]
   [?e :sms/displayName ?displayName]])


;; TODO translate schematic rules to attribute predicates;
;; Then add fun to transform graph data to install attribute preds


(defn transform-dca-config [config]
  {:dcc (get-in config [:config :dcc :name])
   :data_model_url (get-in config [:config :dcc :data_model_url])})


(defn get-model-graphs
  "Get model graphs, ones unable to be retrieved are nil and removed"
  [configs]
  (->>(map transform-dca-config configs)
      (map #(assoc % :data_model (read-json (% :data_model_url) key-fn)))
      (remove #(nil? (% :data_model)))
      (map #(assoc % :graph (good-graph (% :data_model) (:dcc %))))
      (mapv #(% :graph))))


(defn load-models [conn gs]
  (doseq [g gs]
    (d/transact! conn g)))


(defn run-query [conn q]
  (d/q q (d/db conn)))

;(def conn (d/get-conn "/tmp/datalevin/mydb" dlvn-schema))
