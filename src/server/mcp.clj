(ns server.mcp
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [accent.registry :refer [tool-registry deftool tools->mcp-format]]
            [accent.tools :as tools]
            [io.modelcontext.clojure-sdk.stdio-server :as io-server]))

(def all-tools
  (-> @tool-registry
      (tools->mcp-format)))

;; TODO: assess state after setup to select and expose tools more intelligently.
;; For example, do not expose Synapse tools if user has not provided Synapse creds.
;; Currently exposes all tools in registry.

(def accent-server-spec
  {:name "accent: AI-Assisted Curation/Content ENhancement Tools",
   :version "0.6.0",
   :tools all-tools})

(defn -main
  [& _args]
  (setup)
  (let [server-id (random-uuid)]
    (println "Starting the *accent* MCP server...")
    @(io-server/run! (assoc accent-server-spec :server-id server-id))))
