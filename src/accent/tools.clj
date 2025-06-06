(ns accent.tools
  (:require [accent.registry :as registry]
            [curate.synapse :refer [syn curate-dataset create-folder get-table-sample get-entity-wiki get-entity-schema get-user-name query-table set-annotations]]
            [curate.util :as cu]
            [database.arachne :as arachne]
            [cheshire.core :as json]
            [malli.core :as m]))

;; =============================================================================
;; Define and Register Synapse Tools
;; =============================================================================

(defn get-table-context-handler
  "Combine retrieval of table schema and Wiki doc as table context"
  [{:keys [table_id]}]
  (let [schema (get-table-sample @syn table_id)
        doc (get-entity-wiki @syn table_id)
        text (str {:schema schema :doc doc})] 
    {:text text
     :type "text"
     :isError false}))

(registry/deftool :get-table-context
  "Use this to confirm the availability of a Synapse table, retrieve its queryable fields (schema), and get any docs that exists for the table. 
   In some cases, the user may not have table access or the available fields may be insufficient for the user question. 
   The returned context can help answer a general question about the table, construct a valid query, or explain why the user question may not be feasible."
  {:type "object"
   :properties
   {"table_id"
    {:type "string"
     :description "Id of the table to use, which should be specified by the user."}}
   :required ["table_id"]}
  :category #{:data-access :synapse}
  :permissions #{:read}
  :handler get-table-context-handler)

;; ----------------------------------------------------------------------------

(defn query-table-handler
  [{:keys [table_id query]}] 
  {:text (str (query-table @syn table_id query))
   :type "text"
   :isError false})

(registry/deftool :query-table
  "Use to query table with SQL to help answer a user question; query should include only queryable fields; only a subset of valid SQL is allowed -- do not include update clauses."
  {:type "object"
   :properties
   {"table_id"
    {:type "string"
     :description "Table id, e.g. 'syn5464523'"}
    "query"
    {:type "string"
     :description "A valid SQL query."}}
   :required ["table_id" "query"]}
  :category #{:data-access :synapse}
  :permissions #{:read}
  :handler query-table-handler)

;; ----------------------------------------------------------------------------

(defn get-wiki-handler
  [{:keys [id]}]
  {:text (get-entity-wiki @syn id)
   :type   "text"
   :isError false})

(registry/deftool :get-wiki
  "Get the Wiki page, if it exists, for a Synapse entity."
  {:type "object"
   :properties
   {"id"
    {:type "string"
     :description "Synapse entity id, e.g. 'syn12345678'"}}
   :required ["id"]}
  :category #{:documentation :synapse}
  :permissions #{:read}
  :handler get-wiki-handler)

;; ----------------------------------------------------------------------------

(defn commit-handler
  "Store the data as annotations on an existing entity"
  [{:keys [data entity_id collection_id product_name]}]
  (let [ann-map (json/parse-string data)
        id (if entity_id entity_id (create-folder @syn product_name entity_id))
        response (set-annotations @syn id ann-map)]
    (if (= 200 (:status response))
      {:text "Committed successfully."
       :type "text"
       :isError false}
      {:text (str "Failed to store, server returned status " (:status response))
       :type "text"
       :isError true})))

(registry/deftool :commit
  "Add new or updated metadata for an entity (data product) into the Synapse platform."
  {:type "object"
   :properties
   {"data"
    {:type "string"
     :description "JSON string representing the entity."}
    "entity_id"
    {:type "string"
     :description "Id of existing entity to update, or omit to add metadata for a new entity. If omitted, use `collection_id` and `product_name`."}
    "collection_id"
    {:type "string"
     :description "(Only for new entities where `entity_id` does not exist) Provide the id of a Synapse collection where changes can be created."}
    "product_name"
    {:type "string"
     :description "(Only for new entities where `entity_id` does not exist) Suggested name or title for the entity"}}
   :required ["data"]}
  :category #{:data-management :synapse}
  :permissions #{:write}
  :handler commit-handler)

;; ----------------------------------------------------------------------------

(defn get-user-name-handler
  [{:keys [userid]}]
  {:text (str (get-user-name @syn (str userid)))
   :type   "text"
   :isError false})

(registry/deftool :get-user-name
  "Get a user name given a user id (results depend on how the user filled out this field, and in some cases may contain first name only or may be blank)."
  {:type "object"
   :properties
   {"userid"
    {:type "number"
     :description "Ids are integers, e.g. 273960."}}}
  :category #{:user-management :synapse}
  :permissions #{:read}
  :handler get-user-name-handler)

;; =============================================================================
;; Define and Register Arachne Tools
;; =============================================================================

(defn find-matching-attribute-handler
  [{:keys [attribute_uri]}]
  (let [result (arachne/get-same-property attribute_uri)]
    ;; (mu/log ::find-matching-attribute :param attribute_uri)
    (if (or (nil? result) (empty? result))
      {:text "No known matches were found."
       :type "text"}
      {:text (str result)
       :type "text"})))

(registry/deftool 
  :find-matching-attribute
  "Given a source attribute, find the matching/synonymous attribute(s) in a target attribute set."
  {:type "object"
   :properties 
   {"attribute_uri" 
    {:type "string" 
     :description "The source attribute URI, generally of the format '<http://syn.org/{data_standard}/{template}/{attribute}>'."}}
   :required ["attribute_uri"]}
  :category #{:data-mapping}
  :permissions #{:read}
  :handler find-matching-attribute-handler)

;; ----------------------------------------------------------------------------

(defn get-attribute-meta-handler
  [{:keys [attribute_uri]}]
  (let [result (arachne/describe-uri attribute_uri)]
    ;; (mu/log ::get-attribute-meta  :param attribute_uri)
    (if (or (nil? result) (empty? result))
      {:text "No result."
       :type "text"}
      {:text (str result)
       :type "text"})))

(registry/deftool
  :get-attribute-meta
  "Get attribute info using its URI."
  {:type "object"
   :properties 
   {"attribute_uri" 
    {:type "string" 
     :description "The attribute URI, generally of the format '<http://syn.org/{data_standard}/{template}>', e.g. '<http://syn.org/gdc/sample>'."}}
   :required ["attribute_uri"]}
  :category #{:data-mapping}
  :permissions #{:read}
  :handler get-attribute-meta-handler)

;; ----------------------------------------------------------------------------

(defn get-template-meta-handler
  [{:keys [template_uri]}]
  (let [result (arachne/describe-template-columns template_uri)]
    (if (or (nil? result) (empty? result))
      {:text "No result."
       :type "text"}
      {:text (str result)
       :type "text"})))

(registry/deftool
  :get-template-meta
  "Get information about an entity template such as its attributes and order."
  {:type "object"
   :properties 
   {"template_uri" 
    {:type "string" 
     :description "The template URI"}}
   :required ["template_uri"]}
  :category #{:data-mapping}
  :permissions #{:read}
  :handler get-template-meta-handler)

;; ----------------------------------------------------------------------------

(defn list-standard-templates-handler
  [{:keys [standard_uri]}]
  (let [result (arachne/list-templates standard_uri)]
    ;; (mu/log ::list-standard-templates  :param standard_uri)
    (if (or (nil? result) (empty? result))
      {:text "No result."
       :type "text"}
      {:text (str result)
       :type "text"})))

(registry/deftool
  :list-standard-templates
  "List the templates defined by a data standard. Returns template URIs, which can be used with 'get_template_meta' to get more info about a specific template."
  {:type "object" 
   :properties 
   {"standard_uri" 
    {:type "string" 
     :enum ["<http://syn.org/gdc>"] 
     :description "The data standard URI, of the format '<http://syn.org/{data_standard}>', i.e. '<http://syn.org/gdc>'. Currently, only GDC standard is supported."}}
   :required ["standard_uri"]}
  :category #{:data-mapping}
  :permissions #{:read}
  :handler list-standard-templates-handler)

;; =============================================================================
;; Define and Register File Tools
;; =============================================================================

(defn read-file-handler
  [{:keys [file]}]
  (let [file-obj (java.io.File. file)
        size-in-kb (/ (.length file-obj) 1024.0)]
    ;; (mu/log ::read-file  :filename file)
    (if (< size-in-kb 100)
      {:text (slurp file)
       :type "text"
       :isError false}
      {:text "File size exceeds the allowed read limit."
       :type "text"
       :isError true})))

(registry/deftool
  :read-file
  "Read text content from local file or URL."
  {:type "object"
   :properties {"file" {:type "string"
                        :description "Local file path or URL"}}
   :required ["file"]}
  :category #{:io}
  :permissions #{:read}
  :handler read-file-handler)

;; ----------------------------------------------------------------------------

(defn summarize-csv-handler
  [{:keys [file]}]
  (let [result (cu/summarize-manifest file)]
    ;; (mu/log ::summarize-file  :filename file)
    {:text (str result)
     :type "text"
     :isError false}))

(registry/deftool
  :summarize-csv
  "Get summary of data within a csv file, such as columns present, unique values and value ranges. This can handle larger files."
  {:type "object"
   :properties
   {"file"
    {:type "string"
     :description "Local file path or URL"}}
   :required ["file"]}
  :category #{:io}
  :permissions #{:read}
  :handler summarize-csv-handler)

;; ----------------------------------------------------------------------------

(defn submit-data-handler
  "Defaults to storing data to a file."
  [{:keys [data filename]}]
  (let [file (spit filename data)]
    ;; (mu/log ::submit-data :filename filename :message data)
    {:text "Data stored."
     :type "text"}))

(registry/deftool
  :submit-data
  "Submit data content."
  {:type "object"
   :properties {"data" {:type "string" :description "Data to write"}
               "filename" {:type "string" :description "Output filename"}}
   :required ["data" "filename"]}
  :category #{:io}
  :permissions #{:write}
  :handler submit-data-handler)
