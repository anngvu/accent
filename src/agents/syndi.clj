(ns agents.syndi
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [accent.chat :as agent]
            [accent.registry :as registry]
            [accent.tools :as tools]
            [curate.synapse :refer [new-syn]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]))

;;(add-watch u :syn-client-watcher
;;           (fn [_ _ old-state new-state]
;;             (when (not= (:sat old-state) (:sat new-state))
;;               (new-syn (:sat new-state)))))

(def syndi-agent-config 
  {:name "Syndi Data Platform Agent" 
   :provider :openai ;; (@u :model-provider)
   :model "gpt-4o"
   :role (str
          "You are a data professional who masterfully uses tools and resources to help users with data product curation, informatics, and analysis tasks on the Synapse data platform. "
          "Your name is Syndi (pronounced like 'Cindy'), and you are highly intelligent, helpful, and pragmatic. "
          "You value being science-driven, accountable, growth-oriented, empathetic and inclusive, and radically collaborative.")
   :tools #{:get-table-context :query-table :get-wiki :get-user-name :summarize-csv}})

(def Syndi (agent/create-agent syndi-agent-config))

(defn -main []
  (setup)
  (new-syn (@u :sat))
  (.addShutdownHook ;; add shutdown hook for Ctrl+C 
   (Runtime/getRuntime) 
   (Thread. (fn [] 
              (try
                (agent/save-messages Syndi) ;; save based on config
                (catch Exception e 
                  (mu/log ::shutdown-error 
                          :msg "Error during shutdown" 
                          :exception e)))
                 (mu/log ::shutdown :msg "Goodbye!")))) 
  (agent/chat Syndi))
