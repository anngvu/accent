(ns database.dlvn
  (:gen-class)
  (:require [database.upstream :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [cheshire.core :as json]
            [datalevin.core :as d]
            [datalevin.util :as u])
  (:import  [java.util UUID]))


(defonce conn (atom nil))

(def db-dir (u/tmp-dir (str "datalevin-" (UUID/randomUUID))))

(def schematic-model-schema
  ;; :db/ident       :schema/isPartOf
  ;; during import, this is removed because the isPartOf "http://schema.biothings.io"
  ;; is not useful and is technically not true (unless the model has been registered)
  ;; A reference to DCC is much more useful
[{:db/ident       :id
  :db/valueType   :db.type/string
  :db/unique      :db.unique/identity
  :db/doc         "Identifier for the entity"}

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


(def dcc-schema

  [{:db/ident :dcc/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Name of the DCC"}

   {:db/ident :dcc/logo_location
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "URL for the DCC logo"}

   {:db/ident :dcc/data_model_url
    :db/valueType :db.type/string  ; :db.type/uri not supported
    :db/cardinality :db.cardinality/one
    :db/doc "URL for the data model"}

   {:db/ident :dcc/synapse_asset_view
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Synapse asset view"}

   {:db/ident :dcc/data_model_info
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Information about the data model"}

   {:db/ident :dcc/dcc_help_link
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Help link for the DCC"}

   {:db/ident :dcc/logo_link
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Link for the logo"}

   {:db/ident :dcc/portal_help_link
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "Help link for the portal"}

   {:db/ident :dcc/template_menu_config_file
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc "URL to resource defining public templates"}

   {:db/ident :config/schematic
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one
    :db/doc "Ref to a **working** schematic config to use, which may or may not be standard (could reflect dev config)"}

   ;;{:db/ident :config/schematic }
   ])


(def config-schema
  "A configuration is an arrangement of elements in a particular form or combination.
  This attemps a basic model of software app configuration, where what's configured are
  usually the database, frontend, and other services to provide the overall desired functionality."
  [{:db/ident :config/app
    :db/valueType  :db.type/string ; :db.type/uri not currently supported
    :db/cardinality :db.cardinality/one
    :db/doc "App instance URI for which this config applies, e.g. http://localhost:8080 or https://myapp.com"}


   {:db/ident :config/uri
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "URL representing source location and identity of the config"}])


(def schematic-config-schema

  [
    {:db/ident :schematic/manifest_generate
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one
     :db/isComponent true
     :db/doc "Manifest generation configuration"}

    {:db/ident :manifest_generate/output_format
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/doc "Output format for manifest generation"}

    {:db/ident :manifest_generate/use_annotations
     :db/valueType :db.type/boolean
     :db/cardinality :db.cardinality/one
     :db/doc "Whether to use annotations in manifest generation"}

    {:db/ident :schematic/model_validate
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one
     :db/isComponent true
     :db/doc "Model validation configuration"}

    {:db/ident :model_validate/restrict_rules
     :db/valueType :db.type/boolean
     :db/cardinality :db.cardinality/one
     :db/doc "Whether to restrict rules in model validation"}

    {:db/ident :schematic/model_submit
     :db/valueType :db.type/ref
     :db/cardinality :db.cardinality/one
     :db/isComponent true
     :db/doc "Model submission configuration"}

    {:db/ident :model_submit/use_schema_labels
     :db/valueType :db.type/boolean
     :db/cardinality :db.cardinality/one
     :db/doc "Whether to use schema labels in model submission"}

    {:db/ident :model_submit/table_manipulation
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/doc "Table manipulation method"}

    {:db/ident :model_submit/manifest_record_type
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/doc "Manifest record type"}

    {:db/ident :model_submit/hide_blanks
     :db/valueType :db.type/boolean
     :db/cardinality :db.cardinality/one
     :db/doc "Whether to hide blanks"}])

;; ALIASES
;; [:db/add :dcc/name :db/ident :name]


(defn to-dlvn-schema
  "Datalevin schema wants structure {:ns/attr {:db/valueType type ...}}"
  [map-schema]
  (into {} (map (fn [m] {(:db/ident m) (dissoc m :db/ident)}) map-schema)))

(def db-schema
  (into {} (mapcat to-dlvn-schema [schematic-model-schema dcc-schema config-schema schematic-config-schema])))

(defn show-reference-schema
  "Print a schema reference. NOTE Intentionally limited to showing select schema to avoid overload."
   [schema-name]
  (case schema-name
    "data-model" (str schematic-model-schema)
    "schematic-config" (str schematic-config-schema)
    "dcc" (str dcc-schema)
    "No matching schema found."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; GRAPH MODEL TRANSFORMATIONS
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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


(defn rules-map-to-array
  "Coerce a validation rules map to array."
  [input-map]
  (mapv (fn [[k v]]
          (let [key-str (name k)]
            (str key-str (when (not (empty? v)) (str " " v)))))
        input-map))


(defn transform-entity
  "Streamline and correct known inconsistencies in entities:
  rules structured as maps instead of array,
  comments that are nil instead of string."
  [entity]
  (let [type (entity :type)
        rules (entity :sms/validationRules)
        comment (entity :rdfs/comment)
        required (entity :sms/required)
        comp (entity :sms/requiresComponent)]
    (cond-> entity
      required (assoc :sms/required (as-bool required))
      (vector? type) (assoc :type "rdfs:Class")
      (map? rules) (assoc :sms/validationRules (rules-map-to-array rules))
      (nil? comment) (dissoc :rdfs/comment)
      (nil? comp) (dissoc :sms/requiresComponent))))


(defn create-prefix
  [s]
  (let [words (->(str/replace s #"DCC" "")
                 (str/replace #"-.*" "")
                 (str/split #" "))
        first-word (first words)
        initials (map #(str (first %)) (rest words))]
    (->(apply str first-word initials)
       (str/lower-case))))


(defn re-prefix [m old-prefix new-prefix]
  (let [replace-id (fn [id]
                     (if (str/starts-with? id old-prefix)
                       (str new-prefix (subs id (count old-prefix)))
                       id))]
    (walk/postwalk
      (fn [x]
        (if (and (map? x) (:id x))
          (update x :id replace-id)
          x))
      m)))


(defn transform-graph
  "Transform the JSON-LD graph for loading:
  (retyping) strings to other types as needed
  (namespacing) update id prefix + add :dcc attribute"
  [& {:keys [graph dcc]}]
  (->>(map #(re-prefix % "bts" (create-prefix dcc)) graph)
      (map #(assoc % :dcc dcc))
      (map #(transform-entity %))
      (map rm-default)))

;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; OTHER TRANSFORMATIONS
;;
;;;;;;;;;;;;;;;;;;;;;;;;;

(defn namespace-immediate-keys
  ([m] (namespace-immediate-keys m nil))
  ([m parent-key]
   (reduce-kv (fn [res k v]
                (let [new-key (if parent-key
                                (keyword (name parent-key) (name k))
                                k)]
                  (if (map? v)
                    (assoc res new-key (namespace-immediate-keys v k))
                    (assoc res new-key v))))
              {}
              m)))


(defn link-schematic-config
  "For now schematic configs are identified with config source url and linked accordingly."
  [dcc config-url]
  (assoc dcc :config/schematic [:config/uri config-url]))


(defn transform-dcc
  "Transform to a DCC entity from a DCA config map from a collection such as dcc-configs"
  [config]
  (->(select-keys (config :config) [:dcc])
     (namespace-immediate-keys)
     (:dcc)
     (link-schematic-config (config :config_url))))


(defn transform-schematic-config
  "Transform to a schematic config from a DCA config map"
  [config]
  (->(select-keys (config :config) [:schematic])
     (namespace-immediate-keys)
     (:schematic)
     (assoc :config/uri (config :config_url))))


;;;;;;;;;;;;;;;;;;;
;;
;; INIT / LOADING
;;
;;;;;;;;;;;;;;;;;;;;

(defn get-model-url [config]
  (get-in config [:config :dcc :data_model_url]))


(defn graph-from-url
  [url]
  (->>(read-json url key-fn)
      (:graph)))


(defn transform-dca-config [config]
  {:dcc (get-in config [:config :dcc :name])
   :data_model_url (get-model-url config)})


(defn prep-graphs
  "Process seq of DCA configs, import and prep model graphs.
  Ones unable to be retrieved are nil and removed.
  Ids are corrected to use the dcc prefix instead of 'bts:'."
  [configs]
  (->>(map transform-dca-config configs)
      (map #(assoc % :graph (graph-from-url (% :data_model_url))))
      (remove #(nil? (% :graph)))
      (mapv transform-graph )))


(defn load-graphs!
  "Load a collection of graphs, skips problematic graphs."
  [conn graphs]
  (doseq [[idx g] (map-indexed vector graphs)]
    (try
      (d/transact! conn g)
      (catch Exception e
        (println "Failed to transact graph at index" idx "--" (.getMessage e))))))


(defn load-dccs!
  [conn configs]
  (let [dccs (map transform-dcc configs)]
    (d/transact! conn dccs)))


(defn load-schematic-configs!
  [conn configs]
  (let [s-configs (map transform-schematic-config configs)]
    (d/transact! conn s-configs)))


(defn load-dcc-incrementally!
  [conn configs]
  (let [dccs (map transform-dcc configs)]
    (doseq [d dccs]
      (try
        (d/transact! conn [d])
        (catch Exception e
          (print-str "Failed to transact DCC:" d)
          (println "Error:" (.getMessage e)))))))


(defn load-schematic-config-incrementally!
  [conn configs]
  (let [s-configs (map transform-schematic-config configs)]
    (doseq [sc s-configs]
      (try
        (d/transact! conn [sc])
        (catch Exception e
          (print-str "Failed to transact config:" sc)
          (println "Error:" (.getMessage e)))))))


(defn load-graph-incrementally!
  "Load a graph entity-by-entity, mainly to identify entities with issues."
  [conn graph]
  (doseq [entity graph]
    (try
      (d/transact! conn [entity])
      (catch Exception e
        (println "Failed at entity" (entity :rdfs/label) "--" (.getMessage e))))))


(defn run-query
  "Send query to connection conn"
  ([conn q]
   (d/q q (d/db conn)))
  ([conn q variable]
   (d/q q (d/db conn) variable)))


(defn ask-knowledgegraph
  "Tool version of run-query where conn is not explicitly provided,
  query is a string instead of data structure."
  [query-string]
  (let [q (read-string query-string)]
    (run-query @conn q)))


(defn text-search
  "Convenience function for text search."
  [query-text]
  (d/q '[:find ?label ?desc
         :in $ ?q
         :where [(fulltext $ ?q) [[?e ?a ?desc]]] [?e :sms/displayName ?label]]
        (d/db @conn)
        query-text))


(defn write-json-file [data file-path]
  (with-open [writer (io/writer file-path)]
    (json/generate-stream data writer)))


;; STATS
(defn graph-stats
  "Stats for graphs inserted into db. TODO: implement more stats."
  [graphs]
  (count graphs))


(defn init-db!
  "Initialize db with data. Loading order matters. Should be graph, configs, dcc."
  [{:keys [env] :or {env :prod}}]
  (let [url-map {;; :dev "https://raw.githubusercontent.com/Sage-Bionetworks/data_curator_config/?/"
                 :prod "https://raw.githubusercontent.com/Sage-Bionetworks/data_curator_config/prod/"
                 :test "https://raw.githubusercontent.com/Sage-Bionetworks/data_curator_config/staging/"}
        url (get url-map env)
        dcc-configs (get-dcc-configs {:url url})
        graphs (prep-graphs dcc-configs)]
    (reset! conn (d/get-conn db-dir db-schema))
    (load-graphs! @conn graphs)
    (load-schematic-configs! @conn dcc-configs)
    (load-dccs! @conn dcc-configs)))


(defn clear-db! [] (d/clear @conn))


;; Save fallback DCC configs
;;(write-json-file dcc-configs "configs.json")


;;;;;;;;;;;;
;;
;; QUERIES
;;
;;;;;;;;;;;


(defn get-description-with-display-name
  "Get description for given display label,
  which is retrieved (rather unintuitively) via rdfs/comment"
  [display-name dcc]
  (d/q
   '[:find ?desc
     :in $ ?name ?dcc
     :where
     [?e :sms/displayName ?name]
     [?e :rdfs/comment ?desc]
     [?e :dcc ?dcc]]
   (d/db @conn)
   display-name dcc))


(defn get-deps
  "Use to get props given class, or valid values given attribute.
  Constrained to DCC."
  [display-name dcc]
  (d/q
   '[:find ?dep-name
     :in $ ?display-name ?dcc
     :where
     [?e :sms/displayName ?display-name]
     [?e ?attr ?ref]
     [?ref :sms/displayName ?dep-name]
     [?e :dcc ?dcc]]
   (d/db @conn)
   display-name dcc))


(def required?
 '[:find ?e ?val
   :where
   [?e :sms/required ?val]
   [?e :sms/displayName ?displayName]])


(def count-required
'[:find (count ?e)
  :where
  [?e :sms/required true]])

(def count-required-by-dcc
  "TODO: debug"
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


(def get-nf-schematic-config
  '[:find ?param ?val
    :where
    [?dcc :dcc/name "NF-OSI"]
    [?dcc :config/schematic ?config]
    [?config ?ref ?module]
    [?module ?param ?val]])


(defn get-asset-view
  "Lookup asset view given DCC name"
  [dcc]
  (->
   (d/q
   '[:find ?asset-view-id
    :in $ ?dcc
    :where
    [?e :dcc/name ?dcc]
    [?e :dcc/synapse_asset_view ?asset-view-id]]
   (d/db @conn)
   dcc)
   (ffirst)))


;; Helpers for common queries part of workflow

(defn as-vec [result]
  (vec (mapcat identity result)))

(defn get-portal-dataset-props
  "TODO Update with customizable DCC. Hard-coded for now since only possible for NF"
  []
  (as-vec (get-deps "PortalDataset" "NF-OSI")))


(defn as-schema
  "Create a schema given a collection of entity display names by retrieving
  description and enums (valid values)"
  [entities dcc]
  (mapv
   (fn [k] {:name k
            :description (ffirst (get-description-with-display-name k dcc))
            :enum (as-vec (get-deps k dcc))})
   entities))
