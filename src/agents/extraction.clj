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


(def parse_resource_spec
  {:type "function"
   :function
   {:name "parse_resource"
    :description "Use for preprocessing text from a resource URL when the user provides a url instead of plain text."
    :parameters
    {:type "object"
     :properties
     {:url
      {:type "string"
       :description "URL to a resource"}}}
    :required ["url"]}})

(def call_extraction_agent_spec
  {:type "function"
   :function
   {:name "call_extraction_agent"
    :description ""
    :parameters
    {:type "object"
     :properties
     {:input
      {:type "string"
       :description "User-provided input to forward to the extraction agent. Always ask for content if only a json_schema is provided."}}
     {:input_format
      {:type "string"
       :enum ["text" "link"]
       :description "This should be 'text' if the user has provided lines of text, or 'link' if the user provides a url for the content."}}
     {:json_schema
      {:type "string"
       :description "Path to a JSON schema, which could be a URL or a local filepath."}}}
    :required ["input" "input_format" "json_schema"]}})

(defn parse-resource
  "Parse content from various source types: URL, file path, or direct content"
  [source]
  (cond
    (str/starts-with? source "http") 
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

    (or (str/starts-with? source "file:") (.exists (io/file source)))
    {:content (slurp (if (str/starts-with? source "file:") 
                       (subs source 5) 
                       source))}
    :else
    {:content source}))

(defn process-json-schema 
  "Takes either a URL to a json schema or text string of JSON schema and returns a validated json schema as map."
  [json-schema]
  (let [schema-content (:content (parse-resource json-schema))]
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
                  :content "You are an entity extraction agent that can structure content following the JSON schema provided."}]
        custom-json-schema (process-json-schema json-schema)]
    (if (nil? custom-json-schema)
      (fn [_] {:error true :message "Invalid JSON schema provided."})
      (fn [input]
        (let [msg (if (string? input) {:role "user" :content input} input)
              messages (conj messages msg)] 
          {:model "gpt-4o-mini" ;; only gpt-4o-mini or newer gpt-4o models 
           :messages messages
           :stream stream
           :response_format {:type "json_schema" :json_schema {:name "schema" :schema custom-json-schema}}
          }
          )))))

(defn call-extraction-agent
  "Create extraction agent and invoke it with some content"
  [input input-format json-schema]
  (let [text (if (= "text" input-format) input (:content (parse-resource input)))
        extraction-agent (custom-openai-extraction-agent json-schema)]
    (extraction-agent text)))

