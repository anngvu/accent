(ns agents.syndi
  (:gen-class)
  (:require [accent.state :refer [u]]
            [accent.chat :as chat]
            [curate.synapse :refer [new-syn syn curate-dataset create-folder get-table-column-models get-entity-schema query-table set-annotations]]
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
    :description "Use to curate an existing dataset on Synapse; retrieve dataset meta given scope id and, optionally, a manifest id."
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

(def curate_external_entity_spec
  {:type "function"
   :function
   {:name "curate_external_entity"
    :description "Curate an external entity by adding its metadata to a collection in Synapse."
    :parameters
    {:type "object"
     :properties
     {:input_source
      {:type "string"
       :description (str "Source characterizing or representing the external entity; "
                         "it can be a user-provided text passage describing the entity, web page, filepath, or database ID (e.g. 'PMC134567').")}
     :input_representation
      {:type "string"
       :enum ["text" "link" "filepath" "PMCID"]
       :description "Labels the input source type to optimize curation."}
      :collection_id
      {:type "string"
       :description "The collection container id on Synapse with pattern syn[0-9]+."}}}
    :required ["input_source" "input_representation" "collection_id"] }})

(def stage_curated_spec
  {:type "function"
   :function
   {:name "stage_curated"
    :description "Use to stage the curated and enhanced data product for user review and approval."
    :parameters
    {:type "object"
     :properties
     {:product_type {:type "string"
                     :enum ["dataset"]
                     :description "Type of curated product"}
      :metadata {:type "string"
                :description "Metadata content as a JSON string adhering to the data product schema."}}}
    :required ["product_type" "metadata"]}})

(def commit_curated_spec
  {:type "function"
   :function
   {:name "commit_curated"
    :description "Use to put updated or new JSON metadata for curated entity into Synapse. An update should be accompanied by an existing id, while new metadata can be stored if given a container/collection id."
    :parameters
    {:type "object"
     :properties
     {:metadata
      {:type "string"
       :description "JSON string of the curated product metadata."}
      :storage_id
      {:type "string"
       :description "A Synapse id; for updates, the id is an existing entity, while for new entity metadata the id must be the container/collection id."}
      :storage_scope
      {:type "string"
       :enum ["entity" "collection"]
       :description "Indicates whether storage_id is the entity itself or a collection."}
      :product_name
      {:type "string"
       :description "Only needed for new product metadata: provide the name or title for the product if it exists"}}}
    :required ["metadata" "storage_id" "storage_scope"] }})

(def get_queryable_fields_spec
  {:type "function"
   :function
   {:name "get_queryable_fields"
    :description (str "Use this to first confirm the availability of a Synapse table id and its queryable fields to help answer user questions. "
                      "In some cases, the user may not have table access or the available fields may be insufficient for the question. "
                      "Use the result to construct the query or explain why the question cannot be answered.")
    :parameters
    {:type "object"
     :properties
     {:table_id
      {:type "string"
       :description (str "Id of the table to use, which should be specified by the user.")}}}
    :required ["table_id"]}})

(def query_table_spec
  {:type "function"
   :function
   {:name "query_table"
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
  [curate_dataset_spec
   curate_external_entity_spec
   commit_curated_spec
   stage_curated_spec
   get_queryable_fields_spec
   query_table_spec
   call_extraction_agent_spec
   call_viz_agent_spec
   ])

(def anthropic-tools (chat/convert-tools-for-anthropic tools))

(defn get-first-message-content
  "Parses an OpenAI completions response"
  [response-map]
  (when (= 200 (:status response-map))
    (let [body (:body response-map)
          parsed-body (json/parse-string body true)
          first-choice (first (:choices parsed-body))]
      (get-in first-choice [:message :content]))))

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
  (let [schema (get-entity-schema @syn scope_id)
        result (curate-dataset @syn scope_id asset_view)]
    (if (= :success (result :type))
      (do 
        (swap! products assoc-in [:dataset :intermediate] result)
        {:result (str "Retrieved entity schema and initial curated data; your task is transform the data and complete missing properties, adhering to the target schema so that the data can be sent to staging for review.\n"
                  "Target schema:\n" schema "\n\nInitial curated data:\n" (result :result))
         :type :success})
      result)))

(defn wrap-stage-curated
  "Stage the curated entity by displaying it to the user."
  [{:keys [product_type metadata]}]
  (swap! products assoc-in [(keyword product_type) :staging] metadata)
  {:result "Curated entity has been staged for review. Confirm with user if it should be stored using `commit_curated`."
   :data metadata
   :dataspec "viz"
   :type :success})

(defn wrap-commit-curated
  [{:keys [metadata storage_id storage_scope storage_name]}]
  (let [ann-map (json/parse-string metadata)
        name (if storage_name storage_name (str "Name"))
        id (if (= storage_scope "entity") storage_id (create-folder @syn name storage_id))
        response (set-annotations @syn id ann-map)]
    (if (= 200 (:status response))
      {:result "Stored successfully."
       :type :success}
      {:result (str "Failed to store, server returned status " (:status response))
       :type :error})))

(defn wrap-curate-external-entity
  "Workflow abstraction that composes extraction agent, creation of a folder, and storage of metadata."
  [{:keys [input_source input_representation collection_id]}]
  (let [json_schema (get-entity-schema @syn collection_id)
        json_schema_representation "text"]
  (call-extraction-agent input_source input_representation json_schema json_schema_representation)))

(defn wrap-get-queryable-fields
  [{:keys [table_id]}]
  (let [cols (get-table-column-models @syn table_id)]
        ;;table-schema (as-schema cols (@u :dcc))] ;; uses the knowledge graph, and integration is being refactored
    {:result (str cols)
     :type :success}))

(defn wrap-query-table
  [{:keys [table_id query]}]
  {:result (str (query-table @syn table_id query))
   :type   :success})

(defn wrap-call-extraction-agent
  [{:keys [input input_representation json_schema json_schema_representation]}] 
  (-> (call-extraction-agent input input_representation json_schema json_schema_representation) 
      (chat/request-openai-completions :string) 
      (get-first-message-content)))

(defn wrap-call-viz-agent
  [{:keys [request data]}]
  {:result (str "Visualization added.")
   :data (json/parse-string data)
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
                     "commit_curated"         (wrap-commit-curated args)
                     "get_queryable_fields"   (wrap-get-queryable-fields args)
                     "query_table"            (wrap-query-table args)
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

(def openai-init-prompt 
  [{:role "system" 
    :content (str "You are a data professional who helps users with data product curation, management, and analysis on the Synapse platform."
                  "Your name is Syndi (pronounced like 'Cindy')."
                  "To establish crucial context and provide best help, always ask users about a data coordinating center (DCC) they may be affiliated with " 
                  ;; "ascertain the DCC name and asset view by checking with the knowledgebase agent,"
                  "and proactively suggest tools and workflows. Common workflows include:\n"
                  "- curating data products already in Synapse\n"
                  "- curating (meta)data for data products outside of Synapse into Synapse collections\n"
                  "- querying tables within Synapse to answer questions and visualize data\n")}])

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
