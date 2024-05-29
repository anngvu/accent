(ns accent.chat
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [babashka.http-client :as client]
            ;;[bblgum.core :as b]
            [cheshire.core :as json]
            [clojure.java.io :as io]))


(defonce messages (atom [{:role    "system"
                          :content "You are a helpful assistant"}]))


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


(defn request [prompt]
    (swap! messages conj {:role    "user"
                          :content prompt})
    (client/post "https://api.openai.com/v1/chat/completions"
                 {:headers {"Content-Type" "application/json"
                            "Authorization" (str "Bearer " (@u :oak))}
                  :body    (json/generate-string
                              {:model   (@u :model)
                               :messages @messages
                               :tools tools})}))


(defn response [resp]
  (let [resp    (json/parse-string (:body resp) true)
        content (get-in resp [:choices 0 :message :content])
        _       (swap! messages conj {:role    "assistant"
                                      :content content})]
    content))


(defn delegate
  []
  "TODO")


(defn working-chat
  []
  "TODO")


(defn save-chat
  [filename]
  (let [json-str (json/generate-string @messages)]
    (with-open [wr (io/writer filename)]
      (.write wr json-str))))


(defn -main []
  (setup))
