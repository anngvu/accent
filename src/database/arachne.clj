(ns database.arachne
  (:gen-class)
  (:require [arachne.aristotle :as aa]
            [arachne.aristotle.registry :as reg]
            [arachne.aristotle.query :as q]
            [clojure.java.io :as io]
            [csv2rdf.csvw :as csvw])
  (:import [java.io File]
           [java.nio.file Files Path Paths]
           [java.nio.file.attribute FileAttribute]))

(def kg (atom nil))

;; Initialize the graph
(defn init-graph [] (with-out-str (reset! kg (aa/graph :jena-mini))))

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

(defn describe-template-columns
  "Describe columns in template in order"
  [template]
  (let [result
        (reg/with {'g "http://syn.org/"}
              (q/run @kg '[?position ?column]
                `[:bgp
                  [?s :rdf/type :g/ColumnPosition]
                  [?s :g/template ~template]
                  [?s :g/column ?column]
                  [?s :g/position ?position]
                ]
                ))]
    (sort-by first result)
    ))

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

(defn create-temp-file
  "Creates a temporary file with the given prefix and extension"
  [prefix ext]
  (let [temp-file (Files/createTempFile prefix ext (into-array FileAttribute []))]
    (.toString temp-file)))

(defn csv-to-rdf-temp
  "Converts a CSV file to the specified RDF format using dynamically created metadata.

   Parameters:
   - csv-path: Path to the CSV file
   - create-metadata-fn: Function that takes csv-path and returns the path to created metadata file
   - options: Map of optional parameters including:
     - :format - Output format keyword (:turtle, :ntriples, etc.)
     - :mode - Conversion mode (:standard, :minimal, :annotated)
     - :prefix - Prefix for temporary files
     - :temp-name - Base name for the output file"
  [csv-path create-metadata-fn & {:keys [format mode prefix temp-name]
                                 :or {format :turtle
                                      mode :minimal
                                      prefix "csv2rdf"
                                      temp-name "output"}}]
  (let [format-extensions {:turtle ".ttl"
                           :ntriples ".nt"
                           :rdfxml ".rdf"
                           :jsonld ".jsonld"
                           :trig ".trig"
                           :nquads ".nq"}

        ;; Get the appropriate extension
        ext (get format-extensions format ".ttl")

        ;; Create a temporary directory
        temp-dir (Files/createTempDirectory prefix (into-array FileAttribute []))

        ;; Create a file in the temp directory with the appropriate extension
        temp-file (.resolve temp-dir (str temp-name ext))
        temp-file-path (.toString temp-file)

        ;; Convert CSV path to File object
        csv-file (io/file csv-path)]

    (println "Converting" csv-path "to" (name format) "...")
    (println "Writing output to" temp-file-path)

    ;; Generate metadata file using the provided function
    (println "Generating metadata file...")
    (let [metadata-path (create-metadata-fn csv-path)
          metadata-file (io/file metadata-path)]

      (println "Using dynamically created metadata from" metadata-path)
      (println "Conversion mode:" (name mode))

      ;; Convert CSV to RDF and write to the temp file
      (csvw/csv->rdf->file csv-file metadata-file temp-file-path {:mode mode})

      ;; Return both the RDF output path and metadata path for potential cleanup
      {:rdf-path temp-file-path
       :metadata-path metadata-path})))

(defn check-for-metadata-template
  [csv-path])

(defn create-csvw-metadata
  [csv-path]
  ;; Create a temp JSON metadata based on the template
  (let [metadata-path (create-temp-file "metadata" ".json")]
    "sequencing_file_metadata.json"))

(defn load-csv-into-graph
  [csv-path]
  (let [transformed-data (csv-to-rdf-temp csv-path create-csvw-metadata)]
    (load-file-into-graph (transformed-data :rdf-path))))

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
    (q/run graph op)))
