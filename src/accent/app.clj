(ns accent.app
  (:require [server.core :refer [start-server]]
            ))

(defn -main []
  ;; Start the web server
  (start-server)
  (println "Server started on http://localhost:3000"))
