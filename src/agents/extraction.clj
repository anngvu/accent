(ns agents.extraction
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [java.net URL]
           [java.io InputStream]
           [org.apache.tika Tika]
           [org.apache.tika.metadata Metadata]
           [org.apache.tika.parser AutoDetectParser]
           [org.apache.tika.sax BodyContentHandler]
           [org.xml.sax ContentHandler]))


(def call_extraction_agent_spec
  {:type "function"
   :function
   {:name "call_extraction_agent"
    :description "Call the extraction agent to extract information from provided input content and JSON schema. 
                  The extraction agent has access to various databases and local files."
    ;;:strict true ;; TODO: Turn this on when it works; currently breaks
    :parameters
    {:type "object"
     :properties
     {:input
      {:type "string"
       :description (str "User-provided input to forward to the extraction agent. "
                         "The input can be the verbatim text passage, web link, filepath, or database ID (e.g. 'PMC134567').")}
     :input_representation
      {:type "string"
       :enum ["text" "link" "filepath" "PMCID"]
       :description "Characterizes the given input to help the extraction agent select the optimal extraction method."}
     :json_schema
      {:type "string"
       :description "JSON schema given by the user, expected to be a URL or filepath such as 'https://example.org/schema.json' or './schema.json'."}
     :json_schema_representation
      {:type "string" 
       :enum ["link" "filepath"] 
       :description "Characterizes how the JSON schema is provided, as a 'link' for web link or 'filepath' for something resembling a local filepath."}}
    :required ["input" "input_representation" "json_schema" "json_schema_representation"] }}})

(defn pmc-bioc
  [pmcid] 
  (str "https://www.ncbi.nlm.nih.gov/research/bionlp/RESTful/pmcoa.cgi/BioC_xml/" pmcid "/unicode"))

(declare parse-resource)

(defn parse-resource
  "Parse content from various sources: URL, filepath, allowed database ID"
  [source source-type]
  (cond
    (= "link" source-type)
    (let [url (URL. source)
          input-stream (.openStream url)
          parser (AutoDetectParser.)
          handler (BodyContentHandler.)
          metadata (Metadata.)]
      (try
        (.parse parser input-stream handler metadata)
        {:content (.toString handler)
         :metadata (into {} (for [name (.names metadata)]
                              [name (.get metadata name)]))}
        (catch Exception e
          (println "Error parsing URL:" (.getMessage e))
          nil)
        (finally
          (.close input-stream))))

    (= "filepath" source-type)
    (if (.exists (io/file source)) {:content (slurp source)} {:content ""})
    
    (= "PMCID" source-type)
    (parse-resource (pmc-bioc source) "link")
    
    :else
    {:content source}))

(defn process-json-schema 
  "Takes either a web link or filepath to a json schema and returns a validated json schema as map."
  [json-schema json-schema-representation]
  (let [schema-content (:content (parse-resource json-schema json-schema-representation))]
    (try
      (let [parsed-schema (json/parse-string schema-content)]
        (if (and (map? parsed-schema)
                  true)
                 ;; (contains? parsed-schema "type"))
                 ;;(contains? parsed-schema "properties")
          parsed-schema
          (throw (ex-info "Invalid JSON schema structure" {:schema parsed-schema}))))
      (catch Exception e
        (println "Error processing JSON schema:" (.getMessage e))
        nil))))

(defn custom-openai-extraction-agent 
  "Create an extraction agent for custom json schema."
  [json-schema & {:keys [stream] :or {stream false}}] 
  (let [messages [{:role "system"
                  :content "You are a content extraction agent that can structure content adhering to the JSON schema provided."}]] 
      (fn [input]
        (let [msg (if (string? input) {:role "user" :content input} input)
              messages (conj messages msg)] 
          {:model "gpt-4o-mini" ;; only gpt-4o-mini or newer gpt-4o models 
           :messages messages
           :stream stream
           :response_format {:type "json_schema" :json_schema {:name "schema" :schema json-schema}}
          }))))

(defn call-extraction-agent
  "Create extraction agent and invoke it with some content"
  [input input-representation json-schema json-schema-representation]
  (let [text (if (= "text" input-representation) input (:content (parse-resource input input-representation)))
        custom-json-schema (process-json-schema json-schema json-schema-representation)
        extraction-agent (custom-openai-extraction-agent custom-json-schema)]
    (extraction-agent text)))

