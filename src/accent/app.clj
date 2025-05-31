(ns accent.app
  (:gen-class)
  (:require  [clojure.tools.cli :as cli] 
             [server.core :as client]
             [server.mcp :as mcp]
             [com.brunobonacci.mulog :as mu]
            ))

(def cli-options
  [["-h" "--help" "Show help"]])

(defn usage [options-summary]
  (->> ["Usage: java -jar accent.jar <command> [options]"
        ""
        "Commands:"
        "  app          Run with built-in client interface"
        "  mcp-server   Run in MCP server mode"
        ""
        "Options:"
        options-summary]
       (clojure.string/join \newline)))

(defn -main [& args] 
  (let [{:keys [options arguments summary errors]} (cli/parse-opts args cli-options)]
    (mu/start-publisher! {:type :simple-file
                          :filename "/tmp/mulog/events.edn"})
    (cond
      (:help options) 
      (println (usage summary))
      
      errors 
      (do 
        (println "Error:" (first errors)) 
        (println (usage summary)))

      :else
      (let [command (first arguments)
            ;; sub-args (rest arguments)
            ]
        (case command
          "app"          (client/start-server)
          "mcp-server"   (mcp/-main) 
          (client/start-server))))))
  
