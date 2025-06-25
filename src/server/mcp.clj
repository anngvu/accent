(ns server.mcp
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [accent.registry :refer [tool-registry tools->mcp-format prompt-registry prompts->mcp-format]]
            [accent.tools :as tools]
            [accent.prompts]
            [curate.synapse :refer [new-syn]]
            [io.modelcontext.clojure-sdk.stdio-server :as io-server]
            [com.brunobonacci.mulog :as mu]))

(def all-tools
  (-> @tool-registry
      (tools->mcp-format)))

(def all-prompts
  (-> @prompt-registry
      (prompts->mcp-format)))

;; TODO: assess state after setup to select and expose tools more intelligently.
;; For example, do not expose Synapse tools if user has not provided Synapse creds.
;; Currently exposes all tools in registry.

(def accent-server-spec
  {:name "accent: AI-Assisted Curation/Content ENhancement Tools",
   :version "0.7.2",
   :tools all-tools
   :prompts all-prompts})

(defn -main
  [& _args]
  (setup {:ui :external-client})
  (new-syn (@u :sat))
  (alter-var-root #'tools/*schematic-auth-token* (constantly (@u :sat)))
  (let [server-id (random-uuid)]
    (mu/log ::mcp-server :info (str "Starting *accent* MCP server" server-id)) 
    @(io-server/run! (assoc accent-server-spec :server-id server-id))))
