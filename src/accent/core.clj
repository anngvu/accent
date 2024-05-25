(ns accent.core 
  (:gen-class)
  (:require [babashka.http-client :as client]
            [cheshire.core :as json]  
            [clojure.data.csv :as csv]  
            [clojure.java.io :as io]
            [clojure.string :as s]))

(def api-key (System/getenv "OPENAI_API_KEY"))
(def synapse-auth-token (System/getenv "SYNAPSE_AUTH_TOKEN"))

(def model (atom "gpt-3.5-turbo"))
(def user (atom {}))
(def project (atom {}))
(def scope (atom {}))
(def dcc (atom {}))

(def tools [
            {:type "function"
             :function
             {:name "curate_dataset"
              :description "Use this to help user curate a dataset given the dataset id and, optionally, an id for the related manifest"
              :parameters
              {:type "object"
               :properties {
                 :dataset_id {:type "string" :description "The dataset id, something like 'syn12345678'"} 
                              :manifest_id {:type "string" 
                                            :description "The manifest id, something like 'syn12345678'. In most cases, the manifest can be automatically discovered, but when a manifest is not in the expected location the id should be provided."}
               }}
              :required ["dataset_id"] }}
             {:name "ask_database"
              :description (str "Use this to help answer user questions about entities in the different data coordinating centers data models. 
                                 Input should be a valid Datomic query.")
              :parameters
              {:type "object"
               :properties {
                 :query {:type "string" 
                         :description 
                         (str "Datomic query extracting info to answer the user's question." 
                              "Datomic query should be written and returned as plain text using this schema: "
                              "TBD")}}
               :required "query"}}
            ])

(defonce messages (atom [{:role    "system"
                          :content "You are a helpful assistant"}]))


(defn request [prompt]
    (swap! messages conj {:role    "user"
                          :content prompt})
    (client/post "https://api.openai.com/v1/chat/completions"
                 {:headers {"Content-Type" "application/json"
                            "Authorization" (str "Bearer " api-key)}
                  :body    (json/generate-string
                              {:model   @model
                               :messages @messages
                               :tools tools})}))

(defn response [resp]
  (let [resp    (json/parse-string (:body resp) true)
        content (get-in resp [:choices 0 :message :content])
        _       (swap! messages conj {:role    "assistant"
                                      :content content})]
    content))


(defn get-user
  "Get user for the work session"
  []
  (->(client/get "https://repo-prod.prod.sagebase.org/repo/v1/userProfile"
                 {:headers {:Content-type "application/json" :Authorization (str "Bearer " synapse-auth-token)}})
     (:body)
     (json/parse-string true)))

(defn set-user
  "Set user or prompt for valid token"
  [userdata]
  (reset! user userdata))


(defn save-chat
  [filename]
  (let [json-str (json/generate-string @messages)]
    (with-open [wr (io/writer filename)]
      (.write wr json-str))))
