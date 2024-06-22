(ns accent.state
  (:gen-class)
  (:require [babashka.http-client :as client]
            ;;[bblgum.core :as b]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [database.dlvn :refer [run-query conn unique-dccs]]))


(defonce u ;; user config
  (atom
   {:sat (System/getenv "SYNAPSE_AUTH_TOKEN")
    :oak (System/getenv "OPENAI_API_KEY")
    :dcc nil
    :profile nil
    :model "gpt-3.5-turbo"
    :ui :default}))


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
          (println "Hi!")
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


;;(defn token-input-tui
;;  [placeholder]
;;  (s/trim (:result (b/gum :input :password true :placeholder placeholder))))


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
    (prompt-for-api-key)))


(defn choose-dcc-def [options]
  (println "Please choose your DCC by entering the corresponding number:")
  (doseq [i (range (count options))]
    (println (str (inc i) ". " (options i))))
  (let [selection (Integer/parseInt (read-line))]
    (if (and (>= selection 1) (<= selection (count options)))
      (let [dcc (options (dec selection))]
        (swap! u assoc :dcc dcc)
        (println dcc "it is!"))
      (do
        (println "Invalid selection. Please try again.")
        (recur options)))))


;;(defn choose-dcc-tui
;;  [options]
;;  (:result (b/gum :choose options)))
;;


(defn prompt-for-dcc
  []
  (println "Please wait while the app attempts to load the latest configs...")
  (let [options (run-query @conn unique-dccs)]
    (choose-dcc-def (mapv first options))))


(defn current-asset-view
  [dcc configs]
  (->(filter #(= (:name %) dcc) configs)
     (first)
     (get-in [:config :dcc :synapse_asset_view])))


(defn setup
  []
  (check-syn-creds)
  (check-openai-creds)
  (prompt-for-dcc))
