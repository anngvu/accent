(ns accent.core 
  (:gen-class)
  (:require [babashka.http-client :as client]
            [bblgum.core :as b]
            [cheshire.core :as json]  
            [clojure.data.csv :as csv]  
            [clojure.java.io :as io]
            [clojure.string :as s]))


(defonce u ;; user config
  (atom
   {:sat (System/getenv "SYNAPSE_AUTH_TOKEN")
    :oak (System/getenv "OPENAI_API_KEY")
    :dcc nil
    :profile nil
    :model "gpt-3.5-turbo"
    :ui :default}))


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


(defn get-user-profile
  "Get user profile from Synapse using Synapse auth token"
  [sat]
  (->(client/get "https://repo-prod.prod.sagebase.org/repo/v1/userProfile"
                 {:headers {:Content-type "application/json" :Authorization (str "Bearer " sat)}})
     (:body)
     (json/parse-string true)))


(defn valid-user-profile?
  "TODO: Real rigorous checks of the user pofile."
  [profile]
  true)


(defn set-user!
  [sat]
  (try
    (let [profile (get-user-profile (@u sat))]
      ;; validate against expected user profile data
      (if (valid-user-profile? profile)
        (do
          ;; (swap! u assoc :profile profile)
          ;; (swap! u assoc :sat token)
          true)
        (do
          (println "Login failed: Invalid user data.")
          nil)))
    (catch Exception e
      (println "Login failed:" (.getMessage e))
      nil)))


(defn set-api-key!
  "Use for switching between OpenAI API keys, as there can be org/personal/project-specific keys.
  TODO: Check whether valid by making a call to OpenAI service."
  [oak]
  (do
    (swap! u assoc :oak oak)
    true))


(defn token-input-def
  [placeholder]
  (let [console (System/console)
        token (.readPassword console placeholder nil)]
    token))


(defn token-input-tui
  [placeholder]
  (s/trim (:result (b/gum :input :password true :placeholder placeholder))))


(defn prompt-for-sat
  "Prompt for Synapse authentication token."
  []
  (let [sat (token-input-def "Synapse auth token: ")]
    (if (set-user! sat)
      (println "Hi" (get-in @u [:profile :firstName])))))


(defn prompt-for-api-key
  []
  (let [api-key (token-input-def "OpenAI API key: ")]
    (if (set-api-key! api-key)
      true
      (println "No OpenAI API key. Exiting."))))


(defn check-syn-creds []
  (when-not (@u :sat)
     (println "Synapse credentials not detected. Please login.")
     (prompt-for-sat)))


(defn check-openai-creds
  "TODO: Handle if user has no credits left."
  []
  (when-not (@u :oak)
    (println "OpenAI API key not detected. Please provide.")
    (prompt-for-api-key)
    ))


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
  (do
    (check-syn-creds)
    (check-openai-creds)
    ))
