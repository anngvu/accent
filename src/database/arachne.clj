(ns database.arachne
  (:gen-class)
  (:require [arachne.aristotle :as aa]
            [arachne.aristotle.inference :as inf]
            [arachne.aristotle.registry :as reg]
            [arachne.aristotle.query :as q]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [cheshire.core :as json]
            [csv2rdf.csvw :as csvw]
            [database.arachne :as arachne])
  (:import [java.io File]
           [java.nio.file Files Path Paths]
           [java.nio.file.attribute FileAttribute]))

(def kg (atom nil))

(defn mapped-prop-rule
  "NOTE: Don't use owl:samePropertyAs as this does have some implications we don't want"
  []
  (reg/with {'g "http://syn.org/"}
    (inf/rule
     :name "Same CDE ID implies :mapping relation"
     :body '[[?p1 :g/isCDE ?id]
             [?p2 :g/isCDE ?id]
             (not= ?p1 ?p2)]
    :head '[[?p1 :g/mapping ?p2]]
    :dir :forward)
    (inf/rule
     :name ":mapping is a symmetric property"
     :body '[[?p1 :g/mapping ?p2]]
     :head '[[?p2 :g/mapping ?p1]]
     :dir :forward)))

;; Initialize the graph
(defn init-graph
  []
  (let [rules [inf/table-all]
        ;rules (conj [inf/table-all] (mapped-prop-rule))
        ]
    (with-out-str
      (reset! kg (aa/graph :jena-rules rules)))))

(init-graph)

(defn load-file-into-graph [file-path]
  (swap! kg (fn [current-graph]
                       (aa/read current-graph file-path))))

(defn get-all-turtle-files
  "Get all turtle files from resources directory"
  []
  (let [resources-dir (File. "resources/rdf")
        exists? (.exists resources-dir)
        is-dir? (.isDirectory resources-dir)]
    (if (and exists? is-dir?)
      (->> (.listFiles resources-dir)
           (filter #(.endsWith (.getName %) ".ttl"))
           (map #(.getAbsolutePath %)))
      (do
        (println "Resources directory not found or not a directory")
        []))))

(defn load-all-turtle-files
  "Create a Jena-mini graph and load all ttl files from directory"
  []
  (let [ttl (get-all-turtle-files)]
    (if (empty? ttl)
      (println "No .ttl files found!")
      (do
        (println (str "Loading " (count ttl) " resource files into graph..."))
        (doseq [file ttl]
          (println (str "Loading " file))
          (load-file-into-graph file))))))

(load-all-turtle-files)

(defn count-triples [graph]
  (count (iterator-seq (.find (.getRawGraph graph)))))

;; Check
(println "Arachne knowledge graph created with" (count-triples @kg) "triples")

(defn get-prop-mapping
  [p]
  (reg/with {'g "http://syn.org/"}
    (q/run @kg '[?s]
    `[:bgp [?s :g/mapping ~p]])))

(defn get-labels
  []
  (q/run @kg '[?s ?o]
       '[:bgp [?s :rdfs/label ?o]]))

(defn describe-uri
  [uri]
  (reg/with {'g "http://syn.org/"}
            (q/run @kg '[?p ?o]
                   `[:bgp [~uri ?p ?o]])))

(defn get-same-property
  "Get matching property property based on potentially same CDE"
  [uri]
  (reg/with {'g "http://syn.org/"}
            (q/run @kg '[?match ?match_label]
                   `[:bgp [~uri :g/isCDE ?cde]
                     [?match :g/isCDE ?cde]
                     [?match :rdfs/label ?match_label]])))

(defn get-same-property-with-label 
  [label]
  (reg/with {'g "http://syn.org/"}
            (q/run @kg '[?match ?match_label]
            '[:bgp [?s :rdfs/label ?label] 
              [?s :g/isCDE ?cde]
              [?match :g/isCDE ?cde]
              [?match :rdfs/label ?match_label]] 
            `{?label ~label})))

(defn get-template-columns-via-node
  "Get template columns (DOES NOT return order info)"
  [template]
  (reg/with {'g "http://syn.org/"}
    (q/run @kg '[?attr ?label]
      `[:bgp
        [?attr :g/node ~template]
        [?attr :rdfs/label ?label]
        ])))

(defn describe-template-columns
  "Describe columns in template in order"
  [template]
  (let [result
        (reg/with {'g "http://syn.org/"}
              (q/run @kg '[?position ?column ?header]
                `[:bgp
                  [?s :rdf/type :g/ColumnPosition]
                  [?s :g/template ~template]
                  [?s :g/column ?column]
                  [?s :g/header ?header]
                  [?s :g/position ?position]
                  ;;[?column :rdfs/label ?label]
                ]
                ))]
    (sort-by first result)
    ))

(defn shared-elements
  "Shared elements in template-1 vs template-2"
  [template-1 template-2]
  (reg/with {'g "http://syn.org/"}
    (q/run @kg '[?attr-1 ?attr-2]
      `[:bgp
        [?attr-1 :g/node ~template-1]
        [?attr-2 :g/node ~template-2]
        [?attr-1 :g/isCDE ?id]
        [?attr-2 :g/isCDE ?id]
        ])))

(defn get-col-position
  "Get position for an attribute within a specific template"
  [attribute template]
  (reg/with {'g "http://syn.org/"}
    (q/run @kg '[?position]
      `[:bgp
        [?s :rdf/type :g/ColumnPosition]
        [?s :g/column ~attribute]
        [?s :g/template ~template]
        [?s :g/position ?position]
        ]
        )))

(defn list-templates
  "List associated templates given what should be a URI for the data standard."
  [uri]
  (reg/with {'g "http://syn.org/"
             'dct "http://purl.org/dc/terms/"}
            (q/run @kg '[?template]
              `[:bgp
                [?template :rdf/type :g/Template]
                [?template :dct/conformsTo ~uri]])))


;; UTILS

(defn- get-file-basename
  "Extracts the basename of a file from its path (removes directory and last extension)."
  [file-path]
  (let [filename (.getName (io/file file-path))]
    (if (.contains filename ".")
      (first (str/split filename #"\.(?=[^\.]+$)")) ; Split on the last dot
      filename)))

(defn- get-csv-headers
  "Reads the first line of a CSV file, considered as headers."
  [csv-path]
  (with-open [reader (io/reader csv-path)]
    (try
      (-> (csv/read-csv reader)
          first)
      (catch Exception e
        (println (str "Error reading CSV headers from " csv-path ": " (.getMessage e)))
        nil))))

(defn generate-csvw-metadata
  "Takes a CSV file path and generates a CSVW JSON metadata string using Cheshire.
   - Property URLs are based on the CSV file's basename for non-dotted headers.
   - Dotted headers like 'entity.property' become 'http://syn.org/ccdi/entity/property'.
   - 'datatype' is omitted from column definitions."
  [csv-path]
  (let [csv-file (io/file csv-path)]
    (if-not (.exists csv-file)
      (throw (java.io.FileNotFoundException. (str "CSV file not found: " csv-path)))
      (let [filename (.getName csv-file)
            basename (get-file-basename csv-path)
            headers (get-csv-headers csv-path)]

        (if (empty? headers)
          (throw (IllegalArgumentException. (str "CSV file is empty, has no header, or is unreadable: " csv-path)))

          (let [column-definitions (mapv
                                    (fn [header]
                                      (let [parts (str/split header #"\." 2) ; Split on the first dot, max 2 parts
                                            prop-url (if (> (count parts) 1) ; If a dot was found and split into at least two parts
                                                       (str "http://syn.org/ccdi/" (first parts) "/" (second parts))
                                                       (str "http://syn.org/ccdi/" basename "/" header))]
                                        {:name header
                                         :titles header
                                         :propertyUrl prop-url}))
                                    headers)

                virtual-column {:name "generated_rdf_type"
                                :virtual true
                                :propertyUrl "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"
                                :valueUrl (str "http://syn.org/ccdi/" basename)}

                all-columns (conj column-definitions virtual-column)

                csvw-map {"@context" "http://www.w3.org/ns/csvw"
                          "url" filename
                          "tableSchema" {"aboutUrl" (str "http://syn.org/ccdi/repo/" basename "/{_row}")
                                         "columns" all-columns}}]
            (json/generate-string csvw-map)))))))

(defn csv-to-rdf
  [csv-path]
  (let [csv-file (io/file csv-path)
        tmp-path (java.io.File/createTempFile "metadata" ".json")
        meta-tmp-file (spit tmp-path (generate-csvw-metadata csv-path))
        meta-file (io/file tmp-path)
        output-file (java.io.File/createTempFile "data" ".ttl")]
  (csvw/csv->rdf->file csv-file meta-file output-file {:mode :minimal})
  output-file))

(defn load-csv-into-graph
  [csv-path]
  (with-out-str (load-file-into-graph (csv-to-rdf csv-path))))

;; TESTS
;;
; (def standard "<http://syn.org/gdc>")
; (def a "<http://syn.org/gdc/study/study_name>")
; (def t "<http://syn.org/gdc/study>")
; (get-same-property-with-label "dbgap_accession")

(def sparql-0
  "PREFIX dct: <http://purl.org/dc/terms/>

   SELECT ?desc
   WHERE {
    <http://syn.org/gdc/study/study_name> dct:description ?desc
   }")

(def sparql-1
  "PREFIX g: <http://syn.org/>
   PREFIX gdc: <http://syn.org/gdc/>
   PREFIX ccdi: <http://syn.org/ccdi/>

  SELECT ?attr
  WHERE {
    ?attr g:node gdc:study
  }")

;; (def sparlq-2
;;  "PREFIX g: <http://syn.org/>
;;   PREFIX dct: <http://purl.org/dct/terms/>
;;
;;  DELETE {
;;   ?prop dct:description ?desc
;;  } INSERT {
;;   ?prop dct:description 'Removed description for props in study template'
;;  } WHERE {
;;    ?prop g:node <http://syn.org/gdc/study>
;;  }")

(defn run-sparql-query [sparql]
  (let [op (q/parse sparql)]
    (q/run @kg op)))

