(ns agents.syndi
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [accent.chat :as chat]
            [curate.synapse :refer [new-syn syn curate-dataset create-folder get-table-sample get-entity-wiki get-entity-schema get-user-name query-table set-annotations]]
            [agents.extraction :refer [call-extraction-agent call_extraction_agent_spec]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]))

;; Set up

(add-watch u :syn-client-watcher
           (fn [_ _ old-state new-state]
             (when (not= (:sat old-state) (:sat new-state))
               (new-syn (:sat new-state)))))

(new-syn (@u :sat))

;; Stores intermediate and final results for curated data products such as datasets, figures, etc.
(defonce products
  (atom nil))

;;;;;;;;;;;;;;;;;;;;;
;; Tool defs
;;;;;;;;;;;;;;;;;;;;;

(def curate_dataset_spec
  {:type "function"
   :function
   {:name "curate_dataset"
    :description "Use to curate an existing dataset on Synapse; retrieve dataset meta given a scope id and, optionally, a manifest id."
    :parameters
    {:type "object"
     :properties
     {:scope_id
      {:type "string"
       :description "The scope id to use, e.g. 'syn12345678'"}
      :asset_view
      {:type "string"
       :description "The asset view id to use, which should have pattern syn[0-9]+"}
      :manifest_id
      {:type "string"
       :description (str "The manifest id, e.g. 'syn224466889'."
                         "While the manifest can be automatically discovered in most cases,"
                         " when not in the expected location the id will need to be provided by the user.")}}}
    :required ["scope_id" "asset_view"] }})

(def stage_curated_spec
  {:type "function"
   :function
   {:name "stage_curated"
    :description "Use to stage a curated and enhanced data product for user review and approval."
    :parameters
    {:type "object"
     :properties
     {:product_type {:type "string"
                     :enum ["dataset"]
                     :description "Type of curated product"}
      :metadata {:type "string"
                :description "Metadata content as a JSON string adhering to the data product schema."}}}
    :required ["product_type" "metadata"]}})

(def commit_spec
  {:type "function"
   :function
   {:name "commit"
    :description (str "Add new or updated metadata for an entity (data product) into the Synapse platform.")
    :parameters
    {:type "object"
     :properties
     {:data
      {:type "string"
       :description "JSON string representing the entity."}
      :entity_id
      {:type "string"
       :description "Id of existing entity to update, or omit to add metadata for a new entity. If omitted, use `collection_id` and `product_name`."}
      :collection_id
      {:type "string"
       :description "(Only for new entities where `entity_id` does not exist) Provide the id of a Synapse collection where changes can be created."}
      :product_name
      {:type "string"
       :description "(Only for new entities where `entity_id` does not exist) Suggested name or title for the entity"}}}
    :required ["data"] }})

(def get_table_context_spec
  {:type "function"
   :function
   {:name "get_table_context"
    :description (str "Use this to first confirm the availability of a Synapse table and its queryable fields (schema). "
                      "This will also return useful table documentation if available. "
                      "In some cases, the user may not have table access or the available fields may be insufficient for the question. "
                      "Use the returned table context to answer a general question about the table, construct a query, or explain why the user question may not be feasible.")
    :parameters
    {:type "object"
     :properties
     {:table_id
      {:type "string"
       :description (str "Id of the table to use, which should be specified by the user.")}}}
    :required ["table_id"]}})

(def get_entity_wiki_spec
  {:type "function"
   :function
   {:name "get_entity_wiki"
    :description "Get the Wiki page, if it exists, for a Synapse entity."
    :parameters
    {:type "object"
     :properties
     {:id
      {:type "string"
       :description "Synapse entity id, e.g. 'syn12345678'"}}}
    :required ["id"] }})

(def get_user_name_spec
  {:type "function"
   :function
   {:name "get_user_name"
    :description "Get a user name given a user id (results depend on how the user filled out this field, and in some cases may contain first name only or may be blank)."
    :parameters
    {:type "object"
     :properties
     {:userid {:type "number"
               :description "Ids are integers, e.g. 273960."}}}}})

(def query_table_spec
  {:type "function"
   :function
   {:name "query_table"
    :description (str "Use to query table with SQL to help answer a user question; "
                      "query should include only queryable fields; "
                      "only a subset of valid SQL is allowed -- do not include update clauses.")
    :parameters
    {:type "object"
     :properties
     {:table_id {:type "string"
                 :description "Table id, e.g. 'syn5464523"}
      :query {:type "string"
              :description "A valid SQL query."}}}}})

(def call_viz_agent_spec
  {:type "function"
   :function
   {:name "call_viz_agent"
    :description "Use visualization agent to plot data in the UI. Data provided must conform to the Vega-Lite specification"
    :parameters
    {:type "object"
     :properties
     {:request {:type "string"
                :description "A summary of the user's visualization intent to help correct or enhance the output if needed and for logging purposes."}
      :data {:type "string"
             :description "JSON parseable string of the vis data in the Vega-Lite spec. Example: `{\"data\":{\"values\":[{\"a\":\"C\",\"b\":2},{\"a\":\"C\",\"b\":7},{\"a\":\"C\",\"b\":4},{\"a\":\"D\",\"b\":1},{\"a\":\"D\",\"b\":2},{\"a\":\"D\",\"b\":6},{\"a\":\"E\",\"b\":8},{\"a\":\"E\",\"b\":4},{\"a\":\"E\",\"b\":7}]},\"mark\":\"bar\",\"encoding\":{\"y\":{\"field\":\"a\",\"type\":\"nominal\"},\"x\":{\"aggregate\":\"average\",\"field\":\"b\",\"type\":\"quantitative\",\"axis\":{\"title\":\"Average of b\"}}}}`"}}}}})

(def call_knowledgebase_agent_spec
  {:type "function"
  :function 
  {:name "call_knowledgebase_agent"
   :description "Ask specialized agent to query a separate knowledgebase of project configurations and reference data standards"
   :parameters
   {:type "object"
   :properties
   {:request {:type "string"
              :description (str "A clear question or request to help the agent perform the query, such as: " 
                           "'What is the asset view for the DCC called NF-OSI?' or "
                           "'Give me the DCC-specific definition for the column named for the HTAN DCC' or"
                           "'Does an ImagingAssayTemplate exist?'")}}}}})

(def tools
  [;;curate_dataset_spec ;; make more flexible
   commit_spec
   stage_curated_spec
   get_table_context_spec
   query_table_spec
   get_entity_wiki_spec
   get_user_name_spec
   call_extraction_agent_spec
   call_viz_agent_spec
   ;; call_knowledgebase_agent_spec ;; being refactored
   ])

(def anthropic-tools (chat/convert-tools-for-anthropic tools true))

;;;;;;;;;;;;;;;;;;;;;;
;; Tool call wrappers
;;;;;;;;;;;;;;;;;;;;;;

;; Wrappers implement further abstractions, do checking as needed of the return, 
;; returns a consistent map with a :result key, which is the result of the tool call,
;; perform any side-effecting actions to save output data outside of messages, 
;; and optionally a :ui key, which is a map of UI elements to be rendered in the UI.

(defn wrap-curate-dataset
  "Implement pipeline and prompt engineering to coordinate curation: 1) get dataset schema, 2) call curate_dataset for some preprocessing, 
  3) store dataset intermediate for reference 4) return various data sources with appropriate prompting"
  [{:keys [scope_id asset_view]}]
  (let [schema (get-entity-schema @syn scope_id)]
    (if (nil? schema)
      {:result "Entity schema is not available. Curation cannot proceed without a valid schema."
       :type :error}
      (let [result (curate-dataset @syn scope_id asset_view)]
        (if (= :success (result :type))
          (do 
            (swap! products assoc-in [:dataset :intermediate] result)
            {:result (str "Info: Schema for data product and initial data retrieved below.\n"
                          "Instructions: Carefully review the properties in the target schema and describe the data product according to the schema before staging for review. "
                          "If 'description', 'comments' or similar field is present, write an informative description tailored for the entity type. "
                          "For example, for a dataset entity, highlight things like interesting features, number or types of samples to help others consume the curated product. "
                          "Target schema:\n" schema "\n\nInitial data:\n" (result :result))
             :type :success})
          result)))))

(defn wrap-stage-curated
  "Stage the curated entity by displaying it to the user."
  [{:keys [product_type metadata]}]
  (let [curated (json/parse-string metadata)]
    (swap! products assoc-in [(keyword product_type) :staging] curated)
    {:result "Curated entity has been staged for review. Confirm with user if it should be stored using `commit`."
     :data curated ;; TODO: validation
     :dataspec "dataset"
     :type :success}))

(defn wrap-commit
  "Store the data as annotations on an existing entity, or create a new entity within a storage scope first if needed.
  TODO: ability to use create-file instead of create-folder"
  [{:keys [data entity_id collection_id product_name]}]
  (let [ann-map (json/parse-string data)
        id (if entity_id entity_id (create-folder @syn product_name entity_id))
        response (set-annotations @syn id ann-map)]
    (println "Metadata stored on/within" collection_id entity_id)
    (if (= 200 (:status response))
      {:result "Committed successfully."
       :type :success}
      {:result (str "Failed to store, server returned status " (:status response))
       :type :error})))

(defn wrap-get-table-context
  "Combine retrieval of table schema and Wiki doc as table context"
  [{:keys [table_id]}]
  (let [schema (get-table-sample @syn table_id)
        doc (get-entity-wiki @syn table_id)]
        ;;table-schema (as-schema cols (@u :dcc))] ;; previous impl using kg
    {:result (str {:schema schema :doc doc })
     :type :success}))

(defn wrap-query-table
  [{:keys [table_id query]}]
  {:result (str (query-table @syn table_id query))
   :type   :success})

(defn wrap-get-entity-wiki
  [{:keys [id]}]
  {:result (get-entity-wiki @syn id)
   :type   :success})

(defn wrap-get-user-name
  [{:keys [userid]}]
  {:result (str (get-user-name @syn (str userid)))
   :type   :success})

(defn wrap-call-extraction-agent
  [{:keys [input input_representation json_schema json_schema_representation]}] 
  (-> (call-extraction-agent input input_representation json_schema json_schema_representation) 
      (chat/request-openai-completions :string) 
      (chat/get-first-message-content)))

(defn wrap-call-viz-agent
  [{:keys [request data]}]
  {:result (str "Visualization added.")
   :data (json/parse-string data) ;; TODO: validation
   :dataspec "vega-lite"
   :type :success})

;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom tool time
;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn with-next-tool-call
  "Applies logic for chaining certain tool calls. Input should be result from `tool-time`
  Currently, stage_curated should be forced after curate_dataset only under certain return types."
  [tool-result]
  (if (and (= "curate_dataset" (tool-result :tool)) (= :success (tool-result :type)))
    (assoc tool-result :next-tool-call "stage_curated")
    tool-result))

(defn tool-time 
  [tool-call]
  (let [call-fn (get-in tool-call [:function :name])
        args    (json/parse-string (get-in tool-call [:function :arguments]) true)]
    (try
      (let [result (case call-fn
                     "curate_dataset"         (wrap-curate-dataset args)
                     "stage_curated"          (wrap-stage-curated args)
                     "commit"                 (wrap-commit args)
                     "get_table_context"      (wrap-get-table-context args)
                     "query_table"            (wrap-query-table args)
                     "get_entity_wiki"        (wrap-get-entity-wiki args)
                     "get_user_name"          (wrap-get-user-name args)
                     "call_extraction_agent"  (wrap-call-extraction-agent args)
                     "call_viz_agent"         (wrap-call-viz-agent args)
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

(def role
  (str 
  "You are a data professional who masterfully uses tools and resources at hand to help users with data product curation, informatics, and analysis tasks on the Synapse platform."
  "Your name is Syndi (pronounced like 'Cindy'), and you see yourself as highly intelligent, helpful, and pragmatic. "
  "You value being science-driven, accountable, growth-oriented, empathetic and inclusive, and radically collaborative."))

(def openai-messages (atom [{:role "system" :content role}]))

(def anthropic-messages (atom []))

(def meta (atom {:system role}))

(def OpenAISyndiAgent 
  (chat/->OpenAIProvider "gpt-4o" 
                   openai-messages
                   tools 
                   tool-time
                   meta))

(def AnthropicSyndiAgent 
  (chat/->AnthropicProvider "claude-3-7-sonnet-latest" 
                      anthropic-messages
                      anthropic-tools 
                      anthropic-tool-time
                      meta))

(defn -main [] 
  (setup)

  (let [agent (if (= (@u :model-provider) "OpenAI")
                OpenAISyndiAgent 
                AnthropicSyndiAgent)]
    
    ;; Add shutdown hook to handle Ctrl+C
    (.addShutdownHook (Runtime/getRuntime)
      (Thread. (fn []
                 (try
                   ;; (chat/save-messages agent) ;; save based on config
                   (catch Exception e
                     (mu/log ::shutdown-error 
                             :msg "Error during shutdown" 
                             :exception e)))
                 (mu/log ::shutdown :msg "Goodbye!"))))
    
    (chat/chat agent)))
