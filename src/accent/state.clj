(ns accent.state
  (:gen-class)
  (:require [babashka.http-client :as client]
            ;;[bblgum.core :as b]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [database.dlvn :refer [init-db! run-query conn unique-dccs]]
            [curate.dataset :refer [new-syn]]))


(defonce u ;; user config
  (atom
   {:sat (System/getenv "SYNAPSE_AUTH_TOKEN")
    :oak (System/getenv "OPENAI_API_KEY")
    :dcc nil
    :profile nil
    :model "gpt-3.5-turbo"
    :ui :default}))


(defn set-syn!
  "Use for switching between Synapse accounts."
  [synapse-auth-token]
  (do
    (new-syn synapse-auth-token)
    (swap! u assoc :sat synapse-auth-token)
    true))


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
    (if (set-syn! sat)
      (println "Synapse set."))))


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
  (let [options (run-query @conn unique-dccs)]
  (choose-dcc-def (mapv first options))))


(defn setup
  [full?]
  (check-openai-creds)
  (when full?
    (check-syn-creds)
    (try
      (println "Please wait while the app attempts to load latest data and configurations...")
      (init-db! {:env :test})
      (println "Loading complete!")
      (prompt-for-dcc)
      (catch Exception e
        (println "An error occurred during setup:" (.getMessage e))
        (System/exit 1)))))
