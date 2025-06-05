(ns accent.state
  (:gen-class)
  (:require [babashka.http-client :as client]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [database.dlvn :refer [init-db! run-query conn unique-dccs get-asset-view]]
            [com.brunobonacci.mulog :as mu]))

(defonce u ;; user config
  (atom
   {:sat nil
    :oak nil
    :aak nil
    :dcc nil
    :asset-view nil
    :profile nil
    :stream false
    :model-provider nil
    :model nil
    :ui :terminal}))

(defn set-api-key!
  "Set API keys for specific model providers (OpenAI or Anthropic)." 
  [provider key] 
  (let [key-keyword (if (= provider "OpenAI") :oak :aak)]
    (swap! u assoc :model-provider provider)
    (swap! u assoc key-keyword key) 
    true))

(defn set-syn-token!
  "Sets Synapse credentials from environment variable or config."
  [{:keys [synapse-auth-token]}]
  (cond 
    synapse-auth-token
    (do
      (swap! u assoc :sat synapse-auth-token)
      (mu/log ::configuration :info "SYNAPSE_AUTH_TOKEN set from config."))
    
    (System/getenv "SYNAPSE_AUTH_TOKEN")
    (do
      (swap! u assoc :sat (System/getenv "SYNAPSE_AUTH_TOKEN"))
      (mu/log ::configuration :info "SYNAPSE_AUTH_TOKEN set from environment variable."))

    :else
    (do
      (mu/log ::configuration :error "No SYNAPSE_AUTH_TOKEN found in environment or config.")
      (System/exit 1))))

(defn set-model-provider! 
  "Checks for model provider based on available API keys in env and config.
  Takes a config map containing :openai-api-key, :anthropic-api-key, :init-model-provider.
  Sets the model provider based on :model-provider if present."
  [{:keys [openai-api-key anthropic-api-key init-model-provider] :as config}]
  (when openai-api-key
    (do 
      (swap! u assoc :oak openai-api-key)
      (mu/log ::configuration :info "OPENAI_API_KEY set from config.")))
  (when anthropic-api-key
    (do
      (swap! u assoc :aak anthropic-api-key)
      (mu/log ::configuration :info "ANTHROPIC_API_KEY set from config.")))
  (let [has-oak (@u :oak)
        has-aak (@u :aak)] 
    (cond 
      (and has-oak has-aak init-model-provider) 
      (do 
        (swap! u assoc :model-provider init-model-provider) 
        (mu/log ::configuration :info "Multiple AI providers available. Preferred provider set to" (@u :model-provider)))
      
      has-oak
      (do
        (swap! u assoc :model-provider :openai) 
        (mu/log ::configuration :info "Preferred provider set to OpenAI"))
      
      has-aak 
      (do 
        (swap! u assoc :model-provider :anthropic) 
        (mu/log ::configuration :info "Preferred provider set to Anthropic"))
      
      :else 
      (do 
        (mu/log ::configuration :error "Application startup failed because no AI providers detected.") 
        (System/exit 1)))))

(def defaults
  "Defaults for some attributes to be applied if not provided in user config."
  {:tools false
   :db-env :prod})

(defn read-config
  "Configuration for accent"
  [filename]
  (try
    (with-open [r (java.io.PushbackReader. (io/reader filename))]
      (edn/read r))
    (catch Exception e
      (mu/log ::configuration :warning (str "Config file" filename "not found. Fall back to environment variables if available."))
      {})))

(defn setup
  [& {:keys [ui] :or {ui :terminal}}]
  (let [user-config (read-config "config.edn")
        config (merge defaults user-config)]
    (when (not= :external-client ui) (set-model-provider! config)) ;; skip for mcp-server mode; will be configured in external client
    (set-syn-token! config)
    (when (config :tools)
      (try
        (do 
          (init-db! {:env (config :db-env)})
          (mu/log ::configuration :info "Knowledgebase created!")) 
        (catch Exception e
          (mu/log ::configuration :error (str "Error during knowledgebase setup:" (.getMessage e)))
          (System/exit 1))))))

