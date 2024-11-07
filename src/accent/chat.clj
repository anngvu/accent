(ns accent.chat
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [curate.dataset :refer [syn curate-dataset get-table-column-models query-table]]
            [database.dlvn :refer [show-reference-schema ask-knowledgegraph get-portal-dataset-props as-schema]]
            [agents.extraction :refer [custom-openai-extraction-agent call-extraction-agent call_extraction_agent_spec]]
            [babashka.http-client :as client]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [org.httpkit.server :as httpkit]
            [com.brunobonacci.mulog :as mu]))


(defn system-prompt
  []
 (str "You are a helpful data management assistant for a data coordinating center (DCC). "
      "Unless specified otherwise, your DCC is '" (@u :dcc) "' and your asset view has id " (@u :asset-view) "."
      "You help users with searching and curating data on Synapse "
      "and working with dcc-specific data dictionaries and configurations."))

(defn init-prompt
  []
  [{:role    "system"
    :content (system-prompt)}])

(defonce messages (atom nil))

(defonce products (atom nil))

(declare parse-openai-response)
(declare parse-anthropic-response)

(defn chat-watcher [key atom old-state new-state]
  (let [last-response (last new-state)]
    (mu/log ::response :message last-response)))

(defn unsend!
  []
  (swap! messages #(if (seq %) (pop %) %)))

;; MODELS

(def openai-models
  "https://platform.openai.com/docs/models"
  {:default "gpt-4o"
   :models {"gpt-3.5-turbo" {:label "GPT-3.5 Turbo"
                             :context 16385}
            "gpt-4o" {:label "GPT-4o"
                      :context 128000}
            "gpt-4" {:label "GPT-4"
                     :context 128000}
            "gpt-4-turbo-preview" {:label "GPT-4 Turbo"
                                   :context 128000}}})

(def anthropic-models
  {:default "claude-3-5-sonnet-20240620"
   :models {"claude-3-5-sonnet-20240620" {:label "Claude 3.5 Sonnet"
                                          :context 200000}
            "claude-3-sonnet-20240229" {:label "Claude 3 Sonnet"
                                        :context 200000}}})

(defn switch-model!
  [model]
  (cond
    (and (get-in anthropic-models [:models model]) (@u :aak))
    (do
      (swap! u assoc :model model)
      (swap! u assoc :model-provider "Anthropic")
      (println "Model switched to" (get-in anthropic-models [:models model :label])))

    (and (get-in openai-models [:models model]) (@u :oak))
    (do
      (swap! u assoc :model model)
      (swap! u assoc :model-provider "OpenAI")
      (println "Model switched to" (get-in openai-models [:models model :label])))

    (or (get-in anthropic-models [:models model])
        (get-in openai-models [:models model]))
    (println "You cannot switch to this model provider because API creds were not set for this provider.")

    :else
    (println "Invalid model. Please choose from the available models.")))

;;;;;;;;;;;;;;;;;;;;;
;; TOOl DEFINITIONS
;;;;;;;;;;;;;;;;;;;;;

(def curate_dataset_spec
  {:type "function"
   :function
   {:name "curate_dataset"
    :description "Use this to help user curate a dataset on the Synapse platform given a scope id and, optionally, a manifest id."
    :parameters
    {:type "object"
     :properties
     {:scope_id
      {:type "string"
       :description "The scope id to use, e.g. 'syn12345678'"}
      :manifest_id
      {:type "string"
       :description (str "The manifest id, e.g. 'syn224466889'."
                         "While the manifest can be automatically discovered in most cases,"
                         " when not in the expected location the id should be provided.")}}}
    :required ["scope_id"] }})

(def get_knowledgegraph_schema_spec
  {:type "function"
   :function
   {:name "get_knowledgegraph_schema"
    :description (str "Retrieve the knowledgegraph schemas in order to help construct a correct query for the user question."
                      "Then use ask_knowledgegraph with the constructed query.")
    :parameters
    {:type "object"
     :properties
     {:schema_name
      {:type "string"
       :enum ["data-model" "schematic-config" "dcc"]
       :description "Name of the desired schema to bring up for reference."}}}
     :required []
     }})

(def ask_knowledgegraph_spec
  {:type "function"
   :function
   {:name "ask_knowledgegraph"
    :description (str "Query knowledgegraph on the user's behalf to answer questions about different centers' reference data models, app configurations, and assets."
                      "Always base queries on the knowledgegraph schema reference returned by get_knowledgegraph_schema. "
                      "Input should be a valid Datomic query.")
    :parameters
    {:type "object"
     :properties
     {:query
      {:type "string"
       :description (str "Datomic query extracting info to answer the user's question."
                         "Datomic query should be written as plain text using the database schema.")}}
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

(def get_queryable_fields_spec
  {:type "function"
   :function
   {:name "get_queryable_fields"
    :description (str "Use this to confirm the availability of a Synapse table id and its queryable fields to help answer user questions. "
                      "In some cases, the available fields may not be sufficient for the question. "
                      "Use the result to construct a query for ask_synapse or explain why the question cannot be answered.")
    :parameters
    {:type "object"
     :properties
     {:table_id
      {:type "string"
       :description (str "By default, use asset view for table id "
                         "unless user provides another id.")}}}
    :required ["table_id"]}})

(def ask_table_spec
  {:type "function"
   :function
   {:name "ask_table"
    :description (str "Use to query table with SQL to help answer a user question; "
                      "query should include only searchable fields;"
                      "a subset of valid SQL is allowed -- do not include update clauses.")
    :parameters
    {:type "object"
     :properties
     {:table_id {:type "string"
                 :description "Table id, e.g. 'syn5464523"}
      :query {:type "string"
              :description "A valid SQL query."}}}}})

(defn convert-tools-for-anthropic
  "Convert OpenAI tools schema to Anthropic tools schema"
  [openai-tools]
  (mapv (fn [tool]
          {:name (get-in tool [:function :name])
           :description (get-in tool [:function :description])
           :input_schema (-> tool
                             (get-in [:function :parameters]))})
        openai-tools))

(def tools
  [curate_dataset_spec
   get_knowledgegraph_schema_spec
   ask_knowledgegraph_spec
   enhance_curation_spec
   get_queryable_fields_spec
   ask_table_spec
   call_extraction_agent_spec])

(def tools-for-anthropic (convert-tools-for-anthropic tools))

;;;;;;;;;;;;;;;;;;;;
;; BASIC CHAT OPS
;;;;;;;;;;;;;;;;;;;;

(defn new-chat-anthropic!
  []
  (reset! messages []))

(defn new-chat-openai!
  "New chat / let's start over."
  []
  (reset! messages (init-prompt)))

(defmulti new-chat! (fn [] (@u :model-provider)))
(defmethod new-chat! "Anthropic" [] (new-chat-anthropic!))
(defmethod new-chat! "OpenAI" [] (new-chat-openai!))

(defn request-openai-completions
  [body]
  (try
    (->(client/post "https://api.openai.com/v1/chat/completions"
                 {:headers {"Content-Type" "application/json"
                            "Authorization" (str "Bearer " (@u :oak))}
                  :body    (json/generate-string body)
                  :as (if (@u :stream) :stream :string)
                  :timeout 25000}))
    (catch Exception e
      (println "Error in request-openai-completions: " (.getMessage e))
      (println "\nbody:" body)
      {:error true
       :message (str (.getMessage e))})))

(defn request-anthropic-messages 
  [body]
  (try
    (client/post "https://api.anthropic.com/v1/messages"
                 {:headers {"Content-Type" "application/json"
                            "x-api-key" (@u :aak)
                            "anthropic-version" "2023-06-01"}
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

(defn prompt-ai-openai
  "Send prompt to OpenAI AI."
  [input & [tool-choice]]
  (let [message (if (string? input) (as-user-message input) input)]
    (swap! messages conj message)
    (let [response (->
      (cond->
         {:model (@u :model)
          :messages @messages
          :tools tools
          :parallel_tool_calls false
          :stream (@u :stream)}
       tool-choice (assoc :tool_choice {:type "function" :function {:name tool-choice}}))
     (request-openai-completions))]
      (if (:error response)
        {:error true
         :message (:message response)}
        response))))

(defn reduce-tool-call-stream
  "Reducer for streamed tool call given thus-accumulated and latest delta."
  [acc delta]
  (->(update-in acc [:function :name] #(str (or % "") (get-in delta [:function :name])))
     (update-in [:function :arguments] #(str (or % "") (get-in delta [:function :arguments])))
     (merge (dissoc delta :function))))

(defn update-collected-tool-calls 
  [response-atom tool-call-delta]
      (let [tindex (tool-call-delta :index)]
          (swap! response-atom update-in [:tool_calls tindex]
              (fn [existing]
                (let [existing (or existing {})]
                (reduce-tool-call-stream existing (dissoc tool-call-delta :index)))))))

(defn recreate-parseable-openai-response
  "Helper for making sure the streamed response assembles back to a non-streamed version
  that can be processed downstream with existing code."
  [collected-response]
  (let [response {:choices 
                  [{:message 
                    {:role "assistant" 
                     :content (collected-response :content)}
                 :finish_reason (collected-response :finish_reason)} ]}]
      (->>
      (cond->
        response
        (not= [] (collected-response :tool_calls)) (assoc-in [:choices 0 :message :tool_calls] (collected-response :tool_calls)))
      (json/generate-string)  
      (hash-map :body)))) 

(defn stream-openai [message tool-choice clients]
  (let [response (prompt-ai-openai message tool-choice)
        reader (io/reader (:body response))
        collected-response (atom {:content "" :tool_calls []})]
    (doseq [line (line-seq reader)]
      (when (not (str/blank? line))
        (when (str/starts-with? line "data: ")
          (let [data (subs line 6)] ;; Remove "data: "
            ;;(println data) ;; inspect stream chunks
            (if (= data "[DONE]")
              (do
                (when clients 
                  (doseq [client @clients] (httpkit/send! client (json/generate-string {:content "\n"}))))
                (parse-openai-response (recreate-parseable-openai-response @collected-response) clients))
              (let [parsed (json/parse-string data true)
                    content (get-in parsed [:choices 0 :delta :content])
                    finish_reason (get-in parsed [:choices 0 :finish_reason])
                    tool_calls (get-in parsed [:choices 0 :delta :tool_calls 0])]
                (when finish_reason 
                  (swap! collected-response assoc :finish_reason finish_reason))
                (when tool_calls
                    (update-collected-tool-calls collected-response tool_calls))
                (when content
                    (when clients (doseq [client @clients] (httpkit/send! client (json/generate-string {:content content}))))
                    (swap! collected-response update :content str content))))))))))
   
(defn prompt-ai-anthropic
  "Send prompt to Anthropic AI, potentially with name of forced tool choice."
  [input & [tool-choice]]
  (let [message (if (string? input) (as-user-message input) input)]
    (swap! messages conj message)
    (let [response (->
                    (cond->
                     {:model (@u :model)
                      :tools tools-for-anthropic
                      :max_tokens 1000
                      :system (system-prompt)
                      :messages @messages
                      :temperature 0
                      :stream false}
                      tool-choice (assoc :tool_choice {:type "tool" :name tool-choice}))
                    (request-anthropic-messages))]
      (if (:error response)
        {:error true
         :message (get-in response [:error :message])
         :data response}
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
;; UTILS
;;;;;;;;;;;;;;;;;;;;;;;

(defn get-first-message-content [response-map]
  (when (= 200 (:status response-map))
    (let [body (:body response-map)
          parsed-body (json/parse-string body true)
          first-choice (first (:choices parsed-body))]
      (get-in first-choice [:message :content]))))


;;;;;;;;;;;;;;;;;;;;;;;
;; TOOL CALL WRAPPERS
;;;;;;;;;;;;;;;;;;;;;;;

(defn wrap-curate-dataset
  "Call with additional args in state, store structured result in data products,
  generate a string representation for chat messages."
  [args]
  (let [scope (args :scope_id)
        asset-view (@u :asset-view)
        dataset-props (get-portal-dataset-props)]
    (swap! products assoc :dataset (curate-dataset @syn scope asset-view dataset-props))
    ;; (swap! products assoc :supplement "") ;; relevant text excerpts to provide more context
    (str (@products :dataset :result)))) ;; (@products :supplement))

(defn wrap-enhance-curation
  "Merge AI-generated data with internal data for curated product,
  generate a string summary response.
  TODO: validation of AI input + flexible logic instead of hard-coding to dataset."
  [args]
  (swap! products update-in [:dataset :result] merge args)
  "Successful update.")

(defn wrap-ask-knowledgegraph
  [args]
  (->>(ask-knowledgegraph (args :query))
      (mapcat identity)
      (vec)
      (str/join ", ")))

(defn wrap-get-queryable-fields
  "Retrieve columns configured for Synapse table,
  look up descriptions / enums in data model, and merge data to provide contextual schema.
  Note: may need to involve schematic configuration as well for some DCCs."
  [{:keys [table_id] :or {table_id (@u :asset-view)}}]
  (let [cols (get-table-column-models @syn table_id)
        table-schema (as-schema cols (@u :dcc))]
    (str table-schema)))

(defn wrap-ask-table
  "Wrap a query to a Synapse table (NOT all of Synapse)"
  [{:keys [table_id query]}]
  (->>(query-table @syn table_id query)
      (str)))

(defn wrap-call-extraction-agent
  [{:keys [input input_representation json_schema json_schema_representation]}] 
  (->(call-extraction-agent input input_representation json_schema json_schema_representation)
     (request-openai-completions)
     (get-first-message-content)))


;;;;;;;;;;;;;;;;;;;;;;;
;; TOOL CALLS
;; ;;;;;;;;;;;;;;;;;;;;

(defn with-next-tool-call
  "Applies logic for chaining certain tool calls. Input should be result from `tool-time`
  Currently, enhance_curation should be forced after curate_dataset only under certain return types.
  TODO: make more elegant since potentially a lot more will be included."
  [tool-result]
  (if (and (= "curate_dataset" (tool-result :tool)) (= :success (tool-result :type)))
    (assoc tool-result :next-tool-call "enhance_curation")
    tool-result))

(defn tool-time
  "Expects a single tool entity for tool time.
  Internal call done by matching to the tool wrapper,
  which should store any applicable result data in state
  and returns a string representation of result."
  [tool-call]
  (let [call-fn (get-in tool-call [:function :name])
        args  (json/parse-string (get-in tool-call [:function :arguments]) true)]
    ;; TODO: validate function calls before calling
    (try
      (let [result (case call-fn
                     "curate_dataset" (wrap-curate-dataset args)
                     "enhance_curation" (wrap-enhance-curation args)
                     "get_knowledgegraph_schema" (show-reference-schema (args :schema_name))
                     "ask_knowledgegraph" (wrap-ask-knowledgegraph args)
                     "get_queryable_fields" (wrap-get-queryable-fields args)
                     "ask_table" (wrap-ask-table args)
                     "call_extraction_agent" (wrap-call-extraction-agent args)
                     (throw (ex-info "Invalid tool function" {:tool call-fn})))]
        (if (map? result)
          (merge  {:tool call-fn } result)
          {:tool call-fn :result result}))
      (catch Exception e
        (println "Error in tool-time" e)
        {:tool call-fn
         :result (.getMessage e)
         :type :error
         :error true}))))

(defn tool-time-anthropic
  "Convert Anthropic tool_use message to resemble OpenAI's tool_call and process with tool-time"
  [tool-use]
  (let [tool-call {:id (:id tool-use)
                        :type "function"
                        :function {:name (:name tool-use)
                                   :arguments (json/generate-string (:input tool-use))}}]
     (tool-time tool-call)))

(defn as-last-message
  [message response]
  (->(assoc message :last true)
     (assoc :total-tokens (get-in response [:usage :total_tokens]))))

(defn add-tool-result-for-openai
  "Intercept tool calls (selecting first of incoming tool calls, ignores parallel calls).
   Does tool call and adds result to response, whether good or error, so AI can handle it."
  [tool-calls & [clients]]
  (let [tool-call (first tool-calls)
        tool-name (get-in tool-call [:function :name])
        result (with-next-tool-call (tool-time tool-call))
        forced-tool (result :next-tool-call)
        msg {:tool_call_id (tool-call :id)
             :role "tool"
             :name tool-name
             :content (result :result)}]
    ; if error key present, content is an error message
    ; and AI will likely retry with another tool call
    (if clients
      (do 
        (doseq [client @clients]
            (httpkit/send! client (json/generate-string {:content (str "Note: Assistant needs to interpret results from " tool-name "\n")})))
        (stream-openai msg forced-tool clients))
      (parse-openai-response (prompt-ai-openai msg forced-tool)))))

(defn add-tool-result-for-anthropic
  "Handle Anthropic's version of tool calls, returning tool result in Anthropic-specific message structure. 
   Anthropic does not have parallel tool calls."
  [tool-use]
  (let [result (with-next-tool-call (tool-time-anthropic tool-use))
        msg {:role "user"
             :content
             [{:type "tool_result"
               :tool_use_id (tool-use :id)
               :content (result :result)}]}]
    (parse-anthropic-response (prompt-ai-anthropic msg (result :next-tool-call)))))

(defn check-openai-finish-reason
  "Inconsistency in the OpenAI response necessitates this check. 
   *Forced* tool calls actually have finish reason 'stop' when one might expect
  'tool_calls' to be the reason (as with regular unforced tool calls)."
  [resp]
  (let [stated (get-in resp [:choices 0 :finish_reason])
        tool_calls (get-in resp [:choices 0 :message :tool_calls])]
    (if tool_calls "tool_calls" stated)))

(defn parse-openai-response
  "Encodes logic for reacting to the AI reply. 
   Everything except tool_calls, which has a loop, returns right away. 
   Internally appends to messages, while returning the last message 
   with possible modifications for later logic (see as-last-message).
   Currently only parses message and finish reason but should also be checking usage."
  [resp & [clients]]
  (if (:error resp)
    (do
      (println "Error occurred:" (:message resp))
      {:role "system"
       :content (str "An error occurred: " (:message resp))})
    (let [resp       (json/parse-string (:body resp) true)
          msg (get-in resp [:choices 0 :message])
          tool-calls (msg :tool_calls)
          finish-reason (check-openai-finish-reason resp)]
      (swap! messages conj msg)
      (case finish-reason
        "length" (as-last-message (peek @messages) resp)
        "tool_calls" (add-tool-result-for-openai tool-calls clients)
        "content_filter" (peek @messages)
        "stop" (peek @messages)))))

(defn get-anthropic-text
  "Get text content for display"
  [content]
  (->(filter #(= "text" (:type %)) content)
     (first)
     (:text)))

(defn get-anthropic-tool-use
  [content]
  (->(filter #(= "tool_use" (:type %)) content)
     (first)))

(defn parse-anthropic-response 
  [resp]
  (if (:error resp)
    (do
      (println "Error occurred:" (:error resp))
      {:role "system"
       :content (str "An error occurred: " (:error resp))})
    (let [resp (json/parse-string (:body resp) true)
          content (:content resp)
          msg {:role "assistant" :content content}
          stop-reason (:stop_reason resp)]
      (swap! messages conj msg)
      (case stop-reason
        "max_tokens" (as-last-message (peek @messages) resp)
        "tool_use" (add-tool-result-for-anthropic (get-anthropic-tool-use content))
        "stop_sequence" (peek @messages)
        "end_turn" (peek @messages)))))

(defmulti parse-response (fn [_] (@u :model-provider)))
(defmethod parse-response "Anthropic" [resp] (parse-anthropic-response resp))
(defmethod parse-response "OpenAI" [resp] (parse-openai-response resp))

(defmulti prompt-ai (fn [_] (@u :model-provider)))
(defmethod prompt-ai "Anthropic" [content] (prompt-ai-anthropic content))
(defmethod prompt-ai "OpenAI" [content] (prompt-ai-openai content))

(defn ask
  "Prompting."
  [content]
  (-> content
      prompt-ai
      parse-response))

(defn prompt-shots
  "EXPERIMENTAL. Create prompting environment allowing some number of shots to get a good result,
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

;;;;;;;;;;;;;;;;;
;; CHAT - MAIN
;;;;;;;;;;;;;;;;;

(defn chat
  "Main chat loop."
  []
  (print "First prompt: ")
  (flush)
  (loop [prompt (read-line)]
    (let [ai-reply (ask prompt)]
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
  (when :logging
    (add-watch messages :log-chat chat-watcher)
    ;;(mu/start-publisher! {:type :console})
    (mu/start-publisher! {:type :simple-file :filename "/tmp/mulog/accent.log"}))
  (chat))
