(ns agents.extraction
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [accent.chat :as chat]
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
       :description (str "Provided input to forward to the extraction agent. "
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
       :enum ["text" "link" "filepath"] 
       :description "Characterizes how the JSON schema is provided: as direct text or a reference web link or local filepath."}}
    :required ["input" "input_representation" "json_schema" "json_schema_representation"] }}})

(def return_result_spec
 {:type "function"
   :function
   {:name "return_result"
    :description (str "Based on user preferences, use to return result of the extraction via an API or as a saved local file. "
                  "Use multiple calls as needed for extraction performed on multiple documents or any time extraction yields multiple JSON artifacts.")
    :parameters
    {:type "object"
     :properties
     {:result
      {:type "string"
       :description "Valid JSON of structured data extracted from a document."}
      :method
      {:type "string" 
       :enum ["file" "api"] 
       :description (str "Method used to return result: write to local .json file (default) or post the JSON payload to a submission API. " 
                     "If API, the endpoint should also be provided.")}
      :endpoint
      {:type "string"
       :description "Endpoint to use for API method, which should be provided by user."}}
    :required ["result" "method"] }}})

(def tools [call_extraction_agent_spec return_result_spec])

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
  "Processes JSON schema text or ref (web link or filepath) with attempt to read and validate before returning schema as a map."
  [json-schema json-schema-representation]
  (let [schema-content (if (= "text" json-schema-representation)
                        json-schema
                        (:content (parse-resource json-schema json-schema-representation)))]
    (try
      (let [parsed-schema (json/parse-string schema-content)]
        (if (and (map? parsed-schema) true)
                  ;; (contains? parsed-schema "type"))
                  ;; (contains? parsed-schema "properties")
          parsed-schema
          (throw (ex-info "Invalid JSON schema structure" {:schema parsed-schema}))))
      (catch Exception e
        (println "Error processing JSON schema:" (.getMessage e))
        nil))))

(defn custom-openai-extraction-agent 
  "Create an extraction agent for custom json schema."
  [^clojure.lang.IPersistentMap json-schema & {:keys [stream] :or {stream false}}] 
  (let [messages [{:role "system"
                  :content "You are an agent that can structure content following the JSON schema provided."}]] 
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

(defn wrap-call-extraction-agent
  [{:keys [input input_representation json_schema json_schema_representation]}] 
  (-> (call-extraction-agent input input_representation json_schema json_schema_representation) 
      (chat/request-openai-completions :string) 
      (chat/get-first-message-content)))

(defn save-json
  [json]
  (let [filename (str "extraction-" (System/currentTimeMillis) ".json")]
    (with-open [wr (io/writer filename)]
      (.write wr json))
    (str "Saved as " filename)))

(defn submit-to-api
  "TODO: Stub function. Posts a JSON payload to API from extraction job"
  [payload endpoint]
  )

(defn wrap-return-result
  [{:keys [result method endpoint]}]
  (if (= "api" method) 
  {:result "Returning result via API is not available at this time."}
  {:result (save-json result)}))

(defn tool-time 
  [tool-call]
  (let [call-fn (get-in tool-call [:function :name])
        args    (json/parse-string (get-in tool-call [:function :arguments]) true)]
    (try
      (let [result (case call-fn
                     "call_extraction_agent"  (wrap-call-extraction-agent args)
                     "return_result" (wrap-return-result args)
                     (throw (ex-info "Invalid tool function" {:tool call-fn})))]
        (if (map? result) (merge  {:tool call-fn} result) {:tool call-fn :result result}))
      (catch Exception e
        {:tool   call-fn
         :result (.getMessage e)
         :type   :error
         :error  true}))))

(def openai-init-prompt 
  [{:role "system" 
    :content (str "You are a helpful agent who manages the extraction of information from content as relevant snippets "
                  "or to structured JSON data (following a specific JSON schema) by assigning extraction jobs to a subordinate agent. "
                  "Requests are typically ad hoc extraction from a single document or multiple documents. "
                  ;;"If a JSON schema is not specified, you can retrieve the content first and suggest a schema based on the content, 
                  ;; in order to always return valid JSON. "
              )}])

(def openai-messages (atom openai-init-prompt))

(def OpenAIExtractionAgent 
  (chat/->OpenAIProvider "gpt-4o" 
                   openai-messages
                   tools 
                   tool-time
                   nil))

(defn -main [] (chat/chat OpenAIExtractionAgent))
