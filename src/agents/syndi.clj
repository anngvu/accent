(ns agents.syndi
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [accent.chat :as chat]
            [curate.synapse :refer [syn curate-dataset get-table-column-models query-table]]
            [database.dlvn :refer [show-reference-schema ask-knowledgegraph get-portal-dataset-props as-schema]]
            [agents.extraction :refer [call-extraction-agent call_extraction_agent_spec]]
            [babashka.http-client :as client]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [org.httpkit.server :as httpkit]
            [com.brunobonacci.mulog :as mu]))

;;;;;;;;;;;;;;;;;;;;;
;; Tool defs
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

(def tools
  [curate_dataset_spec
   get_knowledgegraph_schema_spec
   ask_knowledgegraph_spec
   enhance_curation_spec
   get_queryable_fields_spec
   ask_table_spec
   call_extraction_agent_spec
   ])

(def anthropic-tools (chat/convert-tools-for-anthropic tools))

;;;;;;;;;;;;;;;;;;;;;;
;; Custom tool calls
;;;;;;;;;;;;;;;;;;;;;;

(defn wrap-curate-dataset
  [args]
  (let [scope (args :scope_id)
        asset-view (@u :asset-view)
        dataset-props (get-portal-dataset-props)]
    {:result (str (curate-dataset @syn scope asset-view dataset-props))
     :type   :success}))

(defn wrap-enhance-curation
  [args]
  {:result "Successful update."
   :type   :success})

(defn wrap-ask-knowledgegraph
  [args]
  {:result (->> (ask-knowledgegraph (args :query))
                (mapcat identity)
                (vec)
                (str/join ", "))
   :type   :success})

(defn wrap-get-queryable-fields
  [{:keys [table_id] :or {table_id (@u :asset-view)}}]
  (let [cols (get-table-column-models @syn table_id)
        table-schema (as-schema cols (@u :dcc))]
    {:result (str table-schema)
     :type   :success}))

(defn wrap-ask-table
  [{:keys [table_id query]}]
  {:result (->> (query-table @syn table_id query)
                (str))
   :type   :success})

;; util

(defn get-first-message-content
  "Parses an OpenAI completions response"
  [response-map]
  (when (= 200 (:status response-map))
    (let [body (:body response-map)
          parsed-body (json/parse-string body true)
          first-choice (first (:choices parsed-body))]
      (get-in first-choice [:message :content]))))

(defn wrap-call-extraction-agent
  [{:keys [input input_representation json_schema json_schema_representation]}] 
  (-> (call-extraction-agent input input_representation json_schema json_schema_representation) 
      (chat/request-openai-completions :string) 
      (get-first-message-content)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom tool time
;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn with-next-tool-call
  "Applies logic for chaining certain tool calls. Input should be result from `tool-time`
  Currently, enhance_curation should be forced after curate_dataset only under certain return types."
  [tool-result]
  (if (and (= "curate_dataset" (tool-result :tool)) (= :success (tool-result :type)))
    (assoc tool-result :next-tool-call "enhance_curation")
    tool-result))

(defn tool-time 
  [tool-call]
  (let [call-fn (get-in tool-call [:function :name])
        args    (json/parse-string (get-in tool-call [:function :arguments]) true)]
    (try
      (let [result (case call-fn
                     "curate_dataset"         (wrap-curate-dataset args)
                     "enhance_curation"       (wrap-enhance-curation args)
                     "get_knowledgegraph_schema" {:result (show-reference-schema (args :schema_name))
                                                  :type   :success}
                     "ask_knowledgegraph"     (wrap-ask-knowledgegraph args)
                     "get_queryable_fields"   (wrap-get-queryable-fields args)
                     "ask_table"              (wrap-ask-table args)
                     "call_extraction_agent"  (wrap-call-extraction-agent args)
                     (throw (ex-info "Invalid tool function" {:tool call-fn})))]
        (->
         (if (map? result) (merge  {:tool call-fn} result) {:tool call-fn :result result})
         (with-next-tool-call)))
      (catch Exception e
        {:tool   call-fn
         :result (.getMessage e)
         :type   :error
         :error  true}))))

(defn anthropic-tool-time
  [tool-use]
  (let [tool-call {:id       (:id tool-use)
                   :type     "function"
                   :function {:name      (:name tool-use)
                              :arguments (json/generate-string (:input tool-use))}}]
    (tool-time tool-call)))

;;;;;;;;;;;;;;;;;;;;;
;; Agent
;;;;;;;;;;;;;;;;;;;;;

(def openai-init-prompt 
  [{:role "system" 
    :content (str "You are a data professional who helps users manage and interact with data on the Synapse platform." 
                  "Your name is Syndi (pronounced like 'Cindy')."
                  "To provide the best help, ask users about a data coordinating center (DCC) they may be affiliated with "
                  "and proactively describe and offer to deploy tools at your disposal.")}])

(def openai-messages (atom openai-init-prompt))

(def anthropic-messages (atom []))

(def OpenAISyndiAgent 
  (chat/->OpenAIProvider "gpt-4o" 
                   openai-messages
                   tools 
                   tool-time))

(def AnthropicSyndiAgent 
  (chat/->AnthropicProvider "claude-3-5-sonnet-latest" 
                      anthropic-messages
                      anthropic-tools 
                      anthropic-tool-time))

(def provider-agent
  (if (= (@u :model-provider) "OpenAI")
    OpenAISyndiAgent 
    AnthropicSyndiAgent))

(defn -main [] (chat/chat provider-agent))
