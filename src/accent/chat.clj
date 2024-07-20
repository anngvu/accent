(ns accent.chat
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [curate.dataset :refer [syn curate-dataset]]
            [database.dlvn :refer [show-reference-schema ask-database get-portal-dataset-props]]
            [babashka.http-client :as client]
            ;;[bblgum.core :as b]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [com.brunobonacci.mulog :as mu]))


(defn init-prompt
  []
  [{:role    "system"
    :content (str "You are a helpful assistant for a data coordinating center (DCC). "
                  "Unless specified otherwise, the reference DCC is " (@u :dcc)) }])

(defonce messages (atom nil))

(defonce products (atom nil))


(defn chat-watcher [key atom old-state new-state]
  (let [last-response (last new-state)]
    (mu/log ::response :message last-response)))


;; MODELS

(def gpt-models
  "WIP: Recommendation for models and summary of behavioral differences."
  ["gpt-3.5-turbo"
   "gpt-4o"
   "gpt-4"
   "gpt-4-turbo-preview"])


(defn switch-gpt!
  [model]
  (swap! u assoc :model model))

;;;;;;;;;;;;;;;;;;;;;
;; TOOl DEFINITIONS
;;;;;;;;;;;;;;;;;;;;;

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
       :enum ["data-model" "schematic-config" "dcc"]
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


;;;;;;;;;;;;;;;;;;;;
;; BASIC CHAT OPS
;;;;;;;;;;;;;;;;;;;;

(defn new-chat!
  "New chat / let's start over."
  []
  (reset! messages (init-prompt)))


(defn request-completions [body]
  (try
    (client/post "https://api.openai.com/v1/chat/completions"
                 {:headers {"Content-Type" "application/json"
                            "Authorization" (str "Bearer " (@u :oak))}
                  :body    (json/generate-string body)
                  :timeout 25000}) ; 25 seconds timeout
    (catch Exception e
      {:error true
       :message (str (.getMessage e))})))


(defn as-user-message
  "Structure plain text content"
  [content]
  {:role "user"
   :content content})


(defn prompt-ai
  "Send prompt to ai."
  [input & [tool-choice]]
  (let [message (if (string? input) (as-user-message input) input)]
    (swap! messages conj message)
    (let [response (->
      (cond->
         {:model   (@u :model)
          :messages @messages
          :tools tools
          :parallel_tool_calls false}
       tool-choice (assoc :tool_choice {:type "function" :function {:name tool-choice}}))
     (request-completions))]
      (if (:error response)
        {:error true
         :message (:message response)}
        response))))


(def oops
  "Various responses to communicate that the chat is being terminated."
  ["we're over-limit, operations pause suddenly"
   "we're out of prompting space"
   "we're out of prompting scope"
   "we've encountered operational obstacles preventing success"
   "we're out of possible solutions for now"
   "certain obstacles oppose prompt service"
   "onset of prompting stress"])



(defn save-chat
  [filename]
  (let [json-str (json/generate-string @messages)]
    (with-open [wr (io/writer filename)]
      (.write wr json-str))))


(defn save-chat-offer
  []
  (print (str "If you would like to save the chat data before the program exits, "
                "please type 'Yes' exactly."))
  (println)
  (flush)
  (when (= "Yes" (read-line))
    (let [filename (str "accent_" ".json")]
      (save-chat "chat.json")
      (flush)
      (println "Saved your chat as" filename "!")))
  (print "Please exit now. Program must be restarted to start a new chat."))


(defn print-reply [role content]
  (println)
  (print role "_" content)
  (println))


(defn context-stop
  "When context limit reached let user know and present limited option to save chat.
  TODO: ability to start new chat and carry over a summary of last chat,
  requires proactive interception with a reasonable buffer before context limit reached."
  [last-response]
  (println)
  (println "-- NOTIFICATION --")
  (println
   (str "Hey, it looks like " (rand-nth oops)
        ". Context tokens limit has been reached with " (:total-tokens last-response) " tokens."))
  (save-chat-offer))


;;;;;;;;;;;;;;;;;;;;;;;
;; TOOL CALL OPS
;; ;;;;;;;;;;;;;;;;;;;;

(defn wrap-curate-dataset
  "Call with additional args in state, store structured result in data products,
  generate a string representation for chat messages."
  [args]
  (let [scope (args :scope_id)
        asset-view (@u :asset-view)
        dataset-props (get-portal-dataset-props)]
    (try
      (swap! products assoc :dataset (curate-dataset @syn scope asset-view dataset-props))
      ;; (swap! products assoc :supplement "") ;; relevant text excerpts to provide more context
      (str (@products :dataset) "\n") ;; (@products :supplement))
      )))


(defn wrap-enhance-curation
  "Merge AI-generated data with internal data for curated product,
  generate a string summary response.
  TODO: validation of AI input + flexible logic instead of hard-coding to dataset."
  [args]
  (swap! products update-in [:dataset] merge args)
  ;; (println "wrap-enhance-curation applied")
  "Successful update.")


(defn wrap-ask-database
  [args]
  (->>(ask-database (args :query))
      (mapcat identity)
      (vec)
      (str/join ", ")))


(defn with-next-tool-call
  "Applies logic for chaining certain tool calls. Input should be result from `tool-time`
  Currently, enhance_curation should be forced after curate_dataset only under certain return types.
  TODO: make more elegant since potentially a lot more will be included."
  [tc-result]
  (if (and (= "curate_dataset" (tc-result :tool)) (= :success (tc-result :type)))
    (assoc tc-result :next-tool-call "enhance_curation")
    tc-result))


(defn tool-time
  "Expects a single tool entity for tool time.
  Internal call done by matching to the tool wrapper,
  which should stores any applicable result data in state
  and returns a string representation of result."
  [tool-call]
  (let [call-fn (get-in tool-call [:function :name])
        args  (json/parse-string (get-in tool-call [:function :arguments]) true)]
    ;; TODO: validate function calls before calling
    (try
      (let [result (case call-fn
                     "curate_dataset" (wrap-curate-dataset args)
                     "enhance_curation" (wrap-enhance-curation args)
                     "get_database_schema" (show-reference-schema (args :schema_name))
                     "ask_database" (wrap-ask-database args)
                     (throw (ex-info "Invalid tool function" {:tool call-fn})))]
        (if (map? result)
          (merge  {:tool call-fn } result)
          {:tool call-fn :result result}))
      (catch Exception e
        {:tool call-fn
         :result (.getMessage e)
         :error true}))))


(defn as-last-message
  [message response]
  (->(assoc message :last true)
     (assoc :total-tokens (get-in response [:usage :total_tokens]))))

(declare parse-response)

(defn add-tool-result
  "Intercept tool calls (selecting first of incoming tool calls, ignores parallel calls).
  Add the tool result, whether good or error, so AI can handle it."
  [tool-calls]
  (let [tool-call (first tool-calls)
        result (with-next-tool-call (tool-time tool-call))
        msg {:tool_call_id (tool-call :id)
             :role "tool"
             :name (get-in tool-call [:function :name])
             :content (result :result)}]
    ; if error key present, content is an error message
    ; and AI will likely retry with another tool call
    (parse-response (prompt-ai msg (result :next-tool-call)))))


(defn check-finish-reason
  "*Forced* tool calls actually have finish reason 'stop' when one might expect
  'tool_calls' to be the reason (as with unforced tool calls).
  This checks and outputs finish reason more consistently."
  [resp]
  (let [stated (get-in resp [:choices 0 :finish_reason])
        tool_calls (get-in resp [:choices 0 :message :tool_calls])]
    (if tool_calls "tool_calls" stated)))


(defn parse-response
  "Add response to messages and return other internal representation if applicable.
  Note that everything except tool_calls returns right away;
  tool_calls can enter a bit of a loop that takes longer to resolve."
  [resp]
  (if (:error resp)
    (do
      (println "Error occurred:" (:message resp))
      {:role "system"
       :content (str "An error occurred: " (:message resp))})
    (let [resp       (json/parse-string (:body resp) true)
          msg (get-in resp [:choices 0 :message])
          finish-reason (check-finish-reason resp)]
      (swap! messages conj msg)
      (case finish-reason
        "length" (as-last-message (peek @messages) resp)
        "tool_calls" (add-tool-result (msg :tool_calls))
        "content_filter" (peek @messages) ;; TODO: handle more specifically
        "stop" (peek @messages)))))


(defn reply
  "Prompting at the repl"
  [content]
  (parse-response (prompt-ai content)))

(defn prompt-shots
  "Create prompting environment allowing some number of shots to get a good result,
  e.g. contexts known as 'one-shot' or 'few-shot'.
  Allows adapting prompt environment given that success for different prompts can vary,
  e.g. prompts for working database query can be harder to get right
  than for simple structured data extraction. prompt-fn should return map with :result."
  [prompt-fn max-shots]
  (fn [& args]
    (loop [shots 0]
      (let [result (apply prompt-fn args)]
        (if (or (result :error) (= shots max-shots))
          (parse-response result)
          (recur (inc shots)))))))


(def one-shot-tool-call
  "In practice, number of shots given should vary by tool."
  (prompt-shots add-tool-result 1))



;;;;;;;;;;;;;;;;;
;; CHAT - MAIN
;;;;;;;;;;;;;;;;;


(defn chat
  []
  (print "New message:")
  (flush)
  (loop [prompt (read-line)]
    (let [ai-reply (parse-response (prompt-ai prompt))]
      (if (:final ai-reply)
        (do
          (print-reply "accent" (ai-reply :content))
          (context-stop ai-reply)
        )
        (do
          (print-reply "accent" (ai-reply :content))
          (print "user _ ")
          (flush)
          (recur (read-line)))))))


(defn -main []
  (setup)
  (new-chat!)
  ()
  (when :logging
    (add-watch messages :log-chat chat-watcher)
    ;;(mu/start-publisher! {:type :console})
    (mu/start-publisher! {:type :simple-file :filename "/tmp/mulog/accent.log"}))
  (chat))
