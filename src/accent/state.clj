(ns accent.state
  (:gen-class)
  (:require [babashka.http-client :as client]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [database.dlvn :refer [init-db! run-query conn unique-dccs get-asset-view]]
            [curate.dataset :refer [new-syn]]))


(defonce u ;; user config
  (atom
   {:sat (System/getenv "SYNAPSE_AUTH_TOKEN")
    :oak (System/getenv "OPENAI_API_KEY")
    :aak (System/getenv "ANTHROPIC_API_KEY")
    :dcc nil
    :asset-view nil
    :profile nil
    :stream false
    :model-provider "OpenAI" ;; or Anthropic
    :model "gpt-4o"
    :ui :terminal}))

(add-watch u :syn-client-watcher 
           (fn [_ _ old-state new-state]
             (when (not= (:sat old-state) (:sat new-state))
               (new-syn (:sat new-state)))))

(defn set-api-key!
  "Sey API keys for specific model providers (OpenAI or Anthropic)." 
  [provider key] 
  (let [key-keyword (if (= provider "OpenAI") :oak :aak)]
    (swap! u assoc :model-provider provider)
    (swap! u assoc key-keyword key)
     true))

(defn token-input-def
  [placeholder]
  (let [console (System/console)
        token (.readPassword console placeholder nil)]
    token))

(declare set-syn-token!)

(defn set-syn-token!
  "Sets Synapse credentials from config or environment variable."
  [{:keys [synapse-auth-token]}]
  (cond
    synapse-auth-token
    (swap! u assoc :sat synapse-auth-token)

    (@u :sat)
    true

    :else
    (do
      (println "Synapse credentials not detected in environment variable or config!")
      (println "Please set the SYNAPSE_AUTH_TOKEN environment variable or provide it in the config.")
      (System/exit 1))))

(defn choose-model-provider
  "Prompt user to choose between OpenAI and Anthropic."
  []
  (println "Please choose which model provider to use:")
  (loop []
    (println "Enter 'OpenAI' or 'Anthropic':")
    (let [input (read-line)]
      (if (contains? #{"OpenAI" "Anthropic"} input)
        input
        (do
          (println "Invalid provider choice. Please try again.")
          (recur))))))

(defn prompt-for-api-key
  []
 (let [provider (choose-model-provider) 
       api-key (token-input-def (str provider " API key: "))]
    (if (set-api-key! provider api-key)
      (do
        (println (str provider " API key set successfully."))
        true)
      (do
        (println "Failed to set API key. Please try again.")
        false))))

(defn set-model-provider! 
  "Checks and sets up the model provider based on available API keys and config.
  Takes a config map containing :openai-api-key, :anthropic-api-key, :init-model-provider.
  Prioritizes config values over existing @u values.
  Sets the model provider based on :model-provider if present, otherwise prompts for choice if both services are available."
  [{:keys [openai-api-key anthropic-api-key init-model-provider] :as config}]
  (when openai-api-key
    (swap! u assoc :oak openai-api-key))
  (when anthropic-api-key
    (swap! u assoc :aak anthropic-api-key))
  (let [has-oak (@u :oak)
        has-aak (@u :aak)] 
    (cond 
      (and has-oak has-aak) 
      (do 
        (println "You have API access to both OpenAI and Anthropic services.") 
        (if init-model-provider 
          (do 
            (swap! u assoc :model-provider init-model-provider) 
            (println "Model provider set to" init-model-provider))
          (let [chosen-provider (choose-model-provider)] 
            (swap! u assoc :model-provider chosen-provider) 
            (println "Model provider set to" chosen-provider))))
      has-oak 
      (println "You have an OpenAI API key and can use OpenAI services.")
      
      has-aak 
      (println "You have an Anthropic API key and can use Anthropic services.")
      
      :else 
      (do 
        (println "No API keys detected. Please provide an API key for either OpenAI or Anthropic.") 
        (prompt-for-api-key)))))

(defn choose-dcc-def!
  "User chooses dcc name, which is set in state along with asset view, if found."
  [options]
  (println "Please choose your DCC by entering the corresponding number:")
  (doseq [i (range (count options))]
    (println (str (inc i) ". " (options i))))
  (let [selection (Integer/parseInt (read-line))]
    (if (and (>= selection 1) (<= selection (count options)))
      (let [dcc (options (dec selection))]
        (swap! u assoc :dcc dcc)
        (swap! u assoc :asset-view (get-asset-view dcc))
        (println dcc "it is!"))
      (do
        (println "Invalid selection. Please try again.")
        (recur options)))))

(defn prompt-for-dcc
  []
  (let [options (run-query @conn unique-dccs)]
  (choose-dcc-def! (mapv first options))))

(def fallback-config
  "Default configuration"
  {:tools false
   :db-env :prod})

(defn read-config
  "Configuration for accent"
  [filename]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader filename))]
      (edn/read r))
    (catch Exception e
      (println "Config file" filename "not found or invalid, using fallback.")
      fallback-config)))

(defn setup
  [& {:keys [ui] :or {ui :terminal}}]
  (let [config (read-config "config.edn")
        tools-enabled (:tools config)
        db-env (:db-env config)]
    (set-model-provider! config)
    (when tools-enabled
      (set-syn-token! config)
      (try
        (println "Attempting to pull the *latest* data models and configurations...")
        (init-db! {:env db-env})
        (println "Knowledgebase created!")
        (when (= ui :terminal)
          (prompt-for-dcc)
          (swap! u assoc :ui ui))
        (catch Exception e
          (println "Encountered issue during setup:" (.getMessage e))
          (System/exit 1))))))
