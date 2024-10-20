(ns accent.handler
  (:gen-class)
  (:require [babashka.http-client :as client]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [org.httpkit.server :as httpkit]))


(defn process-openai-stream [response clients]
  (let [reader (io/reader (:body response))
        collected-content (atom "")]
    (println "Started processing stream")
    (doseq [line (line-seq reader)]
      (when (not (str/blank? line))
        (when (str/starts-with? line "data: ")
          (let [data (subs line 6)] ;; remove "data: "
            (if (= data "[DONE]")
              (do
                (doseq [client @clients]
                  (httpkit/send! client "\n"))) ;; stream complete
              (let [parsed (json/parse-string data true)
                    content (get-in parsed [:choices 0 :delta :content])]
                (println parsed)
                (when content
                  ;; Send content to channel
                  ;; (println "Sending content to" (count @clients) "client(s):" content)
                  (doseq [client @clients]
                    (httpkit/send! client content))
                  (swap! collected-content str content))))))))
    ;; Return the collected content 
    @collected-content))