(ns server.core
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [accent.chat :refer [stream-response OpenAIChatAgent]]
            [org.httpkit.server :as httpkit]
            [compojure.core :refer [defroutes GET]]
            [compojure.route :as route]
            [babashka.http-client :as client]
            [cheshire.core :as json]
            [clojure.string :as str]
            [ring.util.response :as response]
            [clojure.java.io :as io]
            [database.dlvn :refer [run-query conn unique-dccs]]
            [hiccup.core :refer [html]]
            [clojure.java.browse :refer [browse-url]]))

(def clients (atom #{}))

(defn options-modal-html []
  (let [dccs ["A"]]; (mapv first (run-query @conn unique-dccs))]
    (html
     [:div#options-modal.modal
      [:div.modal-content
       [:h2 "Select your DCC"]
       [:select#dcc-select
        [:option {:value ""} "Choose a DCC"]
        (for [dcc dccs]
          [:option {:value dcc} dcc])]
       [:button#dcc-submit "Set DCC"]]])))

(defn handle-message [msg channel]
  (let [parsed-msg (json/parse-string msg true)]
    (case (:type parsed-msg)
      "set_dcc"
      (do
        (swap! u assoc :dcc (:dcc parsed-msg)))
        ;; (httpkit/send! channel (json/generate-string {:type "dcc_set" :dcc (:dcc parsed-msg)})))

      "chat"
      (future (stream-response OpenAIChatAgent (:content parsed-msg) nil clients))

      (httpkit/send! channel (json/generate-string {:type "error" :message "Unknown message type"})))))

(defn ws-handler [req]
  (httpkit/with-channel req channel
    (httpkit/send! channel (json/generate-string {:type "connected" :message "Connected to server."}))
    (httpkit/on-receive channel (fn [msg] (handle-message msg clients)))
    (httpkit/on-close channel (fn [status] (swap! clients disj channel)))
    ;; Add client to the set
    (swap! clients conj channel)))

(defroutes app-routes
  (GET "/" [] (response/resource-response "index.html" {:root "public"}))
  (GET "/ws" [] ws-handler)
  (GET "/options-modal" [] (response/response (options-modal-html)))
  (route/resources "/")
  (route/not-found "Not Found"))

(defn start-server []
  (setup :ui :web)
  (swap! u assoc :stream true)
  (let [server (httpkit/run-server app-routes {:port 3000})]
    (browse-url "http://localhost:3000")
    server))
