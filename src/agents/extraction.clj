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


(defonce messages 
  [{:role "system" 
    :content "You are an assistant specializing in extraction tasks."}])

(def parse_resource_spec
  {:type "function"
   :function
   {:name "parse_resource"
    :description "Use for preprocessing text from a resource URL when the user provides a url or path instead of plain text for extraction."
    :parameters
    {:type "object"
     :properties 
     {:url
      {:type "string"
       :description "URL to a resource"}}}
    :required ["url"] }})

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

(defn openai-extraction-agent
  [input messages]
   (let [message (if (string? input) (as-user-message input) input)]
     (swap! messages conj message)
     (let [response (->
                  {:model (@u :model)
                    :messages @messages
                    :tools [parse_resource_spec]
                    :parallel_tool_calls false
                    :stream :false})
                  (request-openai-completions)]
       (if (:error response)
         {:error true
          :message (:message response)}
         response))))




