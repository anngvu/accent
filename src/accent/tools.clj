(ns accent.tools
  (:require [accent.registry :as registry]
            [curate.synapse :refer [syn curate-dataset create-folder get-table-sample get-entity-wiki get-entity-schema get-user-name query-table set-annotations]]
            [curate.util :as cu]
            [database.arachne :as arachne]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [babashka.http-client :as client]
            [malli.core :as m]
            [clojure.java.io :as io]))

;; =============================================================================
;; Define and Register Synapse Tools
;; =============================================================================

(defn get-table-context-handler
  "Combine retrieval of table schema and Wiki doc as table context"
  [{:keys [table_id]}]
  (let [schema (get-table-sample @syn table_id)
        doc (get-entity-wiki @syn table_id)
        text (str {:schema schema :doc doc})] 
    {:type "text"
     :text text}))

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
  {:type "text"
   :text (str (query-table @syn table_id query))})

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
  {:type "text"
   :text (get-entity-wiki @syn id)})

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
      {:type "text"
       :text "Committed successfully."}
      {:type "text"
       :text (str "Failed to store, server returned status " (:status response))})))

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
  {:type "text"
   :text (str (get-user-name @syn (str userid)))})

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
      {:type "text"
       :text "No known matches were found."}
      {:type "text"
       :text (str result)})))

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
      {:type "text"
       :text "No result."}
      {:type "text"
       :text (str result)})))

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
      {:type "text"
       :text "No result."}
      {:type "text"
       :text (str result)})))

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
      {:type "text"
       :text "No result."}
      {:type "text"
       :text (str result)})))

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
      {:type "text"
       :text (slurp file)}
      {:type "text"
       :text "File size exceeds the allowed read limit."})))

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
    {:type "text"
     :text (str result)}))

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
    {:type "text"
     :text "Data stored."}))

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

;; =============================================================================
;; Define and Register Schematic Tools
;; =============================================================================
""
(def ^:dynamic *schematic-auth-token* nil)

;; ----------------------------------------------------------------------------
;; /manifest/generate

(defn generate-manifest-handler
  [{:keys [schema_url title data_type use_annotations dataset_id asset_view
           output_format strict_validation data_model_labels]}]
  (let [params (cond-> {"schema_url" schema_url
                        "data_type" (if (vector? data_type) data_type [data_type])}
                 title (assoc "title" title)
                 use_annotations (assoc "use_annotations" use_annotations)
                 dataset_id (assoc "dataset_id" (if (vector? dataset_id) dataset_id [dataset_id]))
                 asset_view (assoc "asset_view" asset_view)
                 output_format (assoc "output_format" output_format)
                 strict_validation (assoc "strict_validation" strict_validation)
                 data_model_labels (assoc "data_model_labels" data_model_labels))]
    (http/get "https://schematic.api.sagebionetworks.org/v1/manifest/generate"
              {:query-params params
               :headers {"Authorization" (str "Bearer " *schematic-auth-token*)}})))

(registry/deftool :generate-manifest
  "Generate metadata manifest (fillable template file) for a given data model and dataset"
  {:type "object"
   :properties {"schema_url" {:type "string"
                              :description "Data model URL (organization-specific, refer to known configurations)"}
                "title" {:type "string"
                         :description "Title of manifest or title prefix, if making multiple manifests"}
                "data_type" {:type "array"
                             :items {:type "string"}
                             :description "What template/component type to generate. To make all, enter [\"all manifests\"]"}
                "use_annotations" {:type "boolean"
                                   :default false
                                   :description "Use annotations to create possibly filled-in template?"}
                "dataset_id" {:type "array"
                              :items {:type "string"}
                              :description "Dataset ID(s), which should be ids of Synapse folder entities, with format syn[0-9]+"}
                "asset_view" {:type "string"
                              :description "ID of view listing all project data assets (Synapse fileview ID)"}
                "output_format" {:type "string"
                                 :enum ["excel" "google_sheet"] ;;  "dataframe (only if getting existing manifests)" -- remove low-level option
                                 :description "Output format for the manifest"}
                "strict_validation" {:type "boolean"
                                     :default true
                                     :description "Strictness of Google Sheets regex validation (Google Sheets only)"}
                "data_model_labels" {:type "string"
                                     :enum ["display_label" "class_label"]
                                     :default "class_label"
                                     :description "Which label type to use for template"}}
   :required ["schema_url" "data_type"]}
  :category #{:schematic :manifest}
  :permissions #{:read}
  :handler generate-manifest-handler)

;; ----------------------------------------------------------------------------
;; /model/submit

(defn submit-manifest-handler
  [{:keys [schema_url data_model_labels data_type dataset_id manifest_record_type
           restrict_rules hide_blanks asset_view json_str table_manipulation
           table_column_names annotation_keys file_annotations_upload
           project_scope dataset_scope file_path]}]
  (let [params (cond-> {"schema_url" schema_url
                        "dataset_id" dataset_id
                        "restrict_rules" (boolean restrict_rules)
                        "asset_view" asset_view}
                 data_model_labels (assoc "data_model_labels" data_model_labels)
                 data_type (assoc "data_type" data_type)
                 manifest_record_type (assoc "manifest_record_type" manifest_record_type)
                 hide_blanks (assoc "hide_blanks" hide_blanks)
                 json_str (assoc "json_str" json_str)
                 table_manipulation (assoc "table_manipulation" table_manipulation)
                 table_column_names (assoc "table_column_names" table_column_names)
                 annotation_keys (assoc "annotation_keys" annotation_keys)
                 file_annotations_upload (assoc "file_annotations_upload" file_annotations_upload)
                 project_scope (assoc "project_scope" project_scope)
                 dataset_scope (assoc "dataset_scope" dataset_scope))]
    (http/post "https://schematic.api.sagebionetworks.org/v1/model/submit"
               {:query-params params
                :headers {"Authorization" (str "Bearer " *schematic-auth-token*)}
                :multipart [{:name "file_name"
                             :content (io/file file_path)}]})))


(registry/deftool :submit-manifest
  "Submit filled manifest file and store in Synapse. Returns id for manifest if successful."
  {:type "object"
   :properties {"schema_url" {:type "string"
                              :description "Data Model URL"}
                "data_model_labels" {:type "string"
                                     :enum ["display_label" "class_label"]
                                     :default "class_label"
                                     :description "How to set labels in the data model"}
                ; better separation of functionality -- validation shhould use validate endpoint
                ;"data_type" {:type "string"
                ;             :description "Data model template/component name. If given, will validate before submitting."}
                "dataset_id" {:type "string"
                              :description "Dataset SynID where manifest will be stored"}
                "manifest_record_type" {:type "string"
                                        :enum ["file_only" "file_and_entities" "table_and_file" "table_file_and_entities"]
                                        :description "How to store the manifest in Synapse"}
                "restrict_rules" {:type "boolean"
                                  :default false
                                  :description "If true, only use in-house validation rules; if false, use Great Expectations"}
                "hide_blanks" {:type "boolean"
                               :description "Skip annotations with blank values"}
                "asset_view" {:type "string"
                              :description "ID of view listing all project data assets"}
                ;Data can be JSON *or* file; since nearly all users use file, don't surface this as it can cause confusion
                ;"json_str" {:type "string"
                ;            :description "JSON string representation of manifest data"}
                "table_manipulation" {:type "string"
                                      :enum ["replace" "upsert"]
                                      :description "How to handle existing tables with same name"}
                "table_column_names" {:type "string"
                                      :enum ["display_name" "display_label" "class_label"]
                                      :default "class_label"
                                      :description "Format for table column names"}
                "annotation_keys" {:type "string"
                                   :enum ["display_label" "class_label"]
                                   :default "class_label"
                                   :description "Format for annotation keys"}
                "file_annotations_upload" {:type "boolean"
                                           :default true
                                           :description "Whether to add annotations when submitting file-based manifests"}
                "project_scope" {:type "array"
                                 :items {:type "string"}
                                 :description "Subset of projects within asset view relevant for operation"}
                "dataset_scope" {:type "string"
                                 :description "Dataset to validate against for filename validation"}
                "file_path" {:type "string"
                             :description "Local path to manifest file (CSV or JSON) to upload"}}
   :required ["schema_url" "dataset_id" "restrict_rules" "asset_view" "file_path"]}
  :category #{:schematic :manifest :validation}
  :permissions #{:write}
  :handler submit-manifest-handler)

;; ----------------------------------------------------------------------------
;; /model/validate

(defn validate-manifest-handler
  [{:keys [schema_url data_type data_model_labels restrict_rules json_str
           asset_view project_scope dataset_scope file_path]}]
  (let [params (cond-> {"schema_url" schema_url
                        "data_type" data_type}
                 data_model_labels (assoc "data_model_labels" data_model_labels)
                 restrict_rules (assoc "restrict_rules" restrict_rules)
                 json_str (assoc "json_str" json_str)
                 asset_view (assoc "asset_view" asset_view)
                 project_scope (assoc "project_scope" project_scope)
                 dataset_scope (assoc "dataset_scope" dataset_scope))]
    (http/post "https://schematic.api.sagebionetworks.org/v1/model/validate"
               {:query-params params
                :headers {"Authorization" (str "Bearer " *schematic-auth-token*)}
                :multipart [{:name "file_name"
                             :content (io/file file_path)}]})))

(registry/deftool :validate-manifest
  "Validate metadata manifest files against a data model"
  {:type "object"
   :properties {"schema_url" {:type "string"
                              :description "Data Model URL"}
                "data_type" {:type "string"
                             :description "Data Model Component to validate against"}
                "data_model_labels" {:type "string"
                                     :enum ["display_label" "class_label"]
                                     :default "class_label"
                                     :description "How to set labels in the data model"}
                "restrict_rules" {:type "boolean"
                                  :default false
                                  :description "If true, only use in-house validation rules; if false, use Great Expectations"}
                ;"json_str" {:type "string"
                ;            :description "JSON string representation of manifest data to validate"}
                "asset_view" {:type "string"
                              :description "ID of view listing all project data assets (required for cross-manifest validation)"}
                "project_scope" {:type "array"
                                 :items {:type "string"}
                                 :description "Subset of projects within asset view relevant for validation"}
                "dataset_scope" {:type "string"
                                 :description "Dataset to validate against for filename validation"}
                "file_path" {:type "string"
                             :description "Local path to manifest file (CSV or JSON) to validate"}}
   :required ["schema_url" "data_type" "file_path"]}
  :category #{:schematic :manifest :validation}
  :permissions #{:read}
  :handler validate-manifest-handler)
