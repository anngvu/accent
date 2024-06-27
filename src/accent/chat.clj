(ns accent.chat
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [curate.dataset :refer [syn curate-dataset]]
            [database.dlvn :refer [show-reference-schema ask-database]]
            [babashka.http-client :as client]
            ;;[bblgum.core :as b]
            [cheshire.core :as json]
            [clojure.java.io :as io]))


(def init-prompt
  [{:role    "system"
   :content "You are a helpful assistant"}])

(defonce messages (atom init-prompt))

(defonce log (atom []))

(def curate_dataset_spec
  {:type "function"
   :function
   {:name "curate_dataset"
    :description "Use this to help user curate a dataset given a scope and, optionally, a manifest."
    :parameters
    {:type "object"
     :properties
     {:scope_id
      {:type "string"
       :description "The scope id to use, e.g. 'syn12345678'"}
      :manifest_id
      {:type "string"
       :description (str "The manifest id, e.g. 'syn12345678'."
                         "While the manifest can be automatically discovered in most cases,"
                         " when not in the expected location the id should be provided.")}}}
    :required ["scope_id"] }})


(def get_database_schema_spec
  {:type "function"
   :function
   {:name "get_database_schema"
    :description (str "If a database schema reference is needed to help construct a query that answers the user question, "
                      "use this to get the schema first. "
                      "Then use ask_database with the constructed query.")
    :parameters
    {:type "object"
     :properties
     {:schema_name
      {:type "string"
       :enum ["data model" "schematic config"]
       :description "Name of the desired schema to bring up for reference."}}}
     :required []
     }})


(def ask_database_spec
  {:type "function"
   :function
   {:name "ask_database"
    :description (str "Use this to answer user questions about the different data coordinating center data models and entities.
                      Input should be a valid Datomic query.")
    :parameters
    {:type "object"
     :properties
     {:query
      {:type "string"
       :description (str "Datomic query extracting info to answer the user's question."
                         "Datomic query should be written and returned as plain text using the database schema.")}}
     :required ["query"]}}})


(def enhance_curation_spec
  {:type "function"
   :function
   {:name "enhance_curation"
    :description "Given features and related content about an entity, derive additional properties."
    :parameters
    {:type "object"
     :properties
     {:title {:type "string"
              :description "A publishable title for the entity given its features and what's present about it"}
      :description {:type "string"
                    :description "Helpful summary for entity no more than a paragraph long."}
      :other {:type "object"
              :description "Any additional properties and values. Use only properties mentioned."}
      }
     :required ["title" "description"] }}})


(def tools [curate_dataset_spec get_database_schema_spec ask_database_spec enhance_curation_spec])


(defn curate-dataset-wrapper
  "Call with additional args in state + handle errors"
  [args]
  (let [scope (args :scope_id)
        asset-view (@u :asset_view)
        dataset-props ()]
    (try
      (curate-dataset @syn scope asset-view dataset-props)
      (catch Exception e
        (println "Database")))))


(defn ask-database-wrapper
  "Call with handle errors + reprompting as needed"
  [args]
  (try
    (ask-database (args :query))
    (catch Exception e
      (println (str "Failed with " (.getMessage e))))))


(defn reset-chat! []
  (reset! messages init-prompt))

(defn message-ai
  "Send  message as user"
  [prompt]
    (swap! messages conj {:role    "user"
                          :content prompt})
    (client/post "https://api.openai.com/v1/chat/completions"
                 {:headers {"Content-Type" "application/json"
                            "Authorization" (str "Bearer " (@u :oak))}
                  :body    (json/generate-string
                              {:model   (@u :model)
                               :messages @messages
                               :tools tools})}))


(defn reply-required-next [msg]
    (swap! messages conj msg)
    (client/post "https://api.openai.com/v1/chat/completions"
                 {:headers {"Content-Type" "application/json"
                            "Authorization" (str "Bearer " (@u :oak))}
                  :body    (json/generate-string
                              {:model   (@u :model)
                               :messages @messages
                               :tools tools
                               :tool_choice {:type "function" :function {:name "enhance_curation"}}
                })}))


(defn tool-time
  "Tool time! Expects a single tool entity."
  [tool-call]
  (let [call-fn (get-in tool-call [:function :name])
        args  (json/parse-string (get-in tool-call [:function :arguments]) true)]

    ;; TODO: validate call-fn and args here
    (try
      (case call-fn
        "curate_dataset" (curate-dataset-wrapper args)
        "get_database_schema" (show-reference-schema (args :schema_name))
        "ask_database" (ask-database (args :query)))
      (catch Exception e
        (let [error-msg (.getMessage e)
              re-prompt (str "The tool call for function " call-fn
                             " failed with error: " error-msg
                             ". Please provide a corrected tool call.")]
          (message-ai re-prompt)
      )))))


(defn get-message
 "Get message from raw response JSON from chat completion."
  [response]
  (->(json/parse-string (:body response) true)
     (get-in [:choices 0 :message])))


(defn get-role
  [response]
  (->(json/parse-string (:body response) true)
     (get-in [:choices 0 :message :role])))


(defn add-ai-response
  "Add AI response to message history"
  [response]
  (let [msg (get-in response [:choices 0 :message])]
    (swap! messages conj msg)))


(defn tool-call-mock-response
  [resp content]
  (let [resp (json/parse-string (:body resp) true)
        _ (add-ai-response resp)
        tool-call (first (get-in resp [:choices 0 :message :tool_calls]))]
    {:tool_call_id (tool-call :id)
     :role "tool"
     :name (get-in tool-call [:function :name])
     :content (str "Here are features of the successfully curated entity: " content)}))


(defn tool-call-mock [response]
  (let [content (str "IndividualIDs: 50 unique;"
                     "Genomic Coverage: Whole-genome sequencing (WGS);"
                     "Read Depth: 30x;"
                     "Sex: 30 Male/20 Female;"
                     "TumorType: Cutaneous Neurofibroma")
        reply (tool-call-mock-response response content)]
    (reply-required-next reply)))


(defn add-tool-content
  "Tool calls interception (first tool only, does not handle parallel tool calls),
  then add tool content to messages."
  [tool-calls]
  (let [tool-call (first (tool-calls))
        fn-result (tool-time tool-call)]
  {:tool_call_id (tool-call :id)
   :role "tool"
   :name (get-in tool-call [:function :name])
   :content fn-result
   }))


(def oops
  "Various responses to communicate that the chat is being terminated."
  ["we're over-context, operations pause suddenly"
   "we're over-context, out of prompting space"
   "we're over-context, out of prompt suggestions"
   "we're out of prompting scope"
   "we're out of possible solutions for now"
   "obstacles oppose prompt service"
   "operational obstacles prevent success"
   "onset of prompting stress"])


(defn total-tokens
  [response]
  (get-in response [:usage :total_tokens]))


(defn save-chat
  [filename]
  (let [json-str (json/generate-string @messages)]
    (with-open [wr (io/writer filename)]
      (.write wr json-str))))


(defn save-chat-offer
  []
  (print (str "If you would like to save the chat data before the app exits, "
                "please type 'Yes' exactly."))
  (println)
  (flush)
  (when (= "Yes" (read-line))
    (let [filename (str "accent_" ".json")]
      ;;(save-chat "chat.json")
      (flush)
      (println "Saved your chat as" filename "!")))
  (print "Please exit and restart the app to start a new chat."))


(defn context-stop
  "Handle when context limit reached.
  Let user know, currently only allow option to save chat and exit app.
  TODO: ability to start new chat and carry over a summary of last chat,
  requires proactive interception with a reasonable buffer before context limit reached."
  [last-response]
  (println
   (str "Hey, it looks like " (rand-nth oops)
        ". Context tokens limit reached with " (total-tokens last-response) " tokens."))
  (save-chat-offer))


(defn parse-response [resp]
  (let [resp       (json/parse-string (:body resp) true)
        finish-reason (get-in resp [:choices 0 :finish_reason])]
    (case finish-reason
      "length" (assoc resp :final true)
      "tool_calls" (tool-time (get-in resp [:choices 0 :message :tool_calls]))
      "content-filter" (add-ai-response resp) ;; TODO: handle more specifically
      "stop" (add-ai-response resp))
    ))


(defn chat
  []
  (print "New message:")
  (flush)
  (loop [prompt (read-line)]
    (let [resp (parse-response (message-ai prompt))]
      (if (:final resp)
        (context-stop resp)
        (do
          (println)
          (print resp)
          (println)
          (print " ---- next message:")
          (flush)
          (recur (read-line)))))))


(defn -main []
  (setup false)
  (chat))
