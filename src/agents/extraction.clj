(ns agents.extraction
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [accent.chat :refer [as-user-message request-openai-completions]]
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

(defn parse-url [url-string]
  (let [url (URL. url-string)
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
        (.close input-stream)))))

(defn process-json-schema 
  "Takes either a URL to a json schema or text string of JSON schema and returns a validated json schema as map."
  [json-schema]
  (let [schema-content (cond
    (str/starts-with? json-schema "http") (:content (parse-url json-schema))
    (str/starts-with? json-schema "file:") (slurp (subs json-schema 5))
    (.exists (io/file json-schema)) (slurp json-schema)
    :else json-schema)]
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
                  :content "You are an entity extraction agent with access to specialized tools and can structure content following the JSON schema provided."}]
        custom-json-schema (process-json-schema json-schema)]
    (if (nil? custom-json-schema)
      (fn [_ & _args] {:error true :message "Invalid JSON schema provided."})
      (fn [input & _args]
        (let [msg (if (string? input) (as-user-message input) input)
              messages (conj messages msg)] 
          (-> {:model "gpt-4o-mini" ;; only gpt-4o-mini or newer gpt-4o models 
               :messages messages
               :tools [parse_resource_spec]
               :parallel_tool_calls false
               :stream stream
               :response_format {:type "json_schema" :json_schema {:name "research_observation" :schema custom-json-schema}} 
               } 
              (request-openai-completions)))))))

 (defn openai-extraction-agent
  "Create an extraction agent for custom json schema."
  [{:keys [stream] :or {stream false}}]
  (let [messages [{:role "system"
                   :content "You are an entity extraction agent"}]]
    (if false
      (fn [_ & _args] {:error true :message "Invalid JSON schema provided."})
      (fn [input & _args]
        (let [msg (if (string? input) (as-user-message input) input)
              messages (conj messages msg)]
          (-> {:model "gpt-4o-mini" ;; only gpt-4o-mini or newer gpt-4o models 
               :messages messages
               :tools [parse_resource_spec]
               :parallel_tool_calls false
               :stream stream
               }
              (request-openai-completions))))))) 
 

 (def my-agent (custom-openai-extraction-agent "https://raw.githubusercontent.com/nf-osi/nf-research-tools-schema/refs/heads/main/NF-Tools-Schemas/observations/SubmitObservationSchema.json"))

 (defn -main 
   []
   (print "Your request: ")
   (flush)
   (loop [prompt (read-line)]
     (let [ai-reply (my-agent prompt)]
         (do
           (println "accent _ " ai-reply)
           (print "user _ ")
           (flush)
           (recur (read-line))))))

