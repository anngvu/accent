(ns accent.core 
  (:gen-class)
  (:require [babashka.http-client :as client]
            [bblgum.core :as b]
            [cheshire.core :as json]  
            [clojure.data.csv :as csv]  
            [clojure.java.io :as io]
            [clojure.string :as s]))

(def pat (atom (System/getenv "SYNAPSE_AUTH_TOKEN")))
(def api-key (atom (System/getenv "OPENAI_API_KEY")))
(def model (atom "gpt-3.5-turbo")) ;; currently not offered as a configurable option yet
(def user (atom {}))
(def dcc (atom {}))
(def ui (atom :default)) ;; the default interace is basic console input, planned => gum TUI + web

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
                            "Authorization" (str "Bearer " @api-key)}
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
  "Get user info from Synapse"
  [pat]
  (->(client/get "https://repo-prod.prod.sagebase.org/repo/v1/userProfile"
                 {:headers {:Content-type "application/json" :Authorization (str "Bearer " pat)}})
     (:body)
     (json/parse-string true)))

(defn valid-user-data?
  "TODO: More rigorous checks of returned server data."
  [user-data]
  true)

(defn set-user!
  [pat]
  (try
    (let [user-data (get-user pat)]
      ;; validate against expected user profile data
      (if (valid-user-data? user-data)
        (do
          (reset! user user-data)
          (reset! pat (user-data :synapse_auth_token))
          true)
        (do
          (println "Login failed: Invalid user data.")
          nil)))
    (catch Exception e
      (println "Login failed:" (.getMessage e))
      nil)))

(defn set-api-key!
  "TODO: Check whether valid by making a call to OpenAI server,
  similar to current check with Synapse set-user!"
  [k]
  (do
    (reset! api-key k)
    true))

(defn token-input
  [placeholder]
  (s/trim (:result (b/gum :input :password true :placeholder placeholder))))

(defn prompt-for-pat
  "Prompt for Synapse personal access token."
  []
  (loop [attempts 0]
    (if (< attempts 2)
      (do
        (let [pat (token-input "Synapse PAT")]
          (if (set-user! pat)
            (println "Hi")
            (recur (inc attempts)))
          (recur (inc attempts))))
      (println "Maximum attempts reached. Exiting."))))

(defn prompt-for-api-key
  []
  (let [api-key (token-input "OpenAI API key")]
    (if (set-api-key! api-key)
      true
      (println "No OpenAI API key. Exiting."))))

(defn check-syn-creds []
  (when-not @pat
     (println "Synapse credentials not detected. Please login.")
     (prompt-for-pat)))

(defn check-openai-creds
  "TODO: Handle if user has no credits left."
  []
  (when-not @api-key
    (println "OpenAI API key not detected. Please provide.")
    (prompt-for-api-key)
    ))

(defn delegate []
  )

(defn working-chat
  [] true)

(defn save-chat
  [filename]
  (let [json-str (json/generate-string @messages)]
    (with-open [wr (io/writer filename)]
      (.write wr json-str))))

(defn -main []
  (do
    (check-syn-creds)
    (check-openai-creds)
    ))
