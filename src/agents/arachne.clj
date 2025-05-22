(ns agents.arachne
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [accent.chat :as chat]
            [curate.util :as cu]
            [database.arachne :as arachne]
            [cheshire.core :as json]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]))

;;;;;;;;;;;;;;;;;;;;;
;; INTERNAL
;;;;;;;;;;;;;;;;;;;;;

(mu/start-publisher! {:type :simple-file
                      :filename "/tmp/mulog/events.edn"})

;;;;;;;;;;;;;;;;;;;;;
;; Tool defs
;;;;;;;;;;;;;;;;;;;;;

(def find_matching_attribute_spec
  {:type "function"
   :function
   {:name "find_matching_attribute"
    :description (str "Given a source attribute, find a matching attribute in a target attribute set. "
                      "This will return the matching attribute if it exists."
                      )
    :parameters
    {:type "object"
     :properties
     {:attribute_uri
      {:type "string"
       :description "The source attribute URI, should be in the format '<http://syn.org/{data_standard}/{template}/{attribute}>', e.g. '<http://syn.org/ccdi/sample/sample_id>'."}
     }
    }
    :required ["attribute_uri"] }})

(def get_attribute_meta_spec
  {:type "function"
   :function
   {:name "get_attribute_meta"
    :description (str "Get all information for an attribute using its URI.")
    :parameters
    {:type "object"
     :properties
     {:attribute_uri
      {:type "string"
       :description "The attribute URI, generally of the format '<http://syn.org/{data_standard}/{template}>', e.g. '<http://syn.org/gdc/sample>'."}}}
    :required ["attribute_uri"]}})

(def get_template_meta_spec
  {:type "function"
   :function
   {:name "get_template_meta"
    :description (str "Get information about an entity template such as its attributes (columns) and their order. Only available for GDC templates.")
    :parameters
    {:type "object"
     :properties
     {:template_uri
      {:type "string"
       :description "The template URI, generally of the format '<http://syn.org/{data_standard}/{template}>', e.g. '<http://syn.org/gdc/sample>'."}}}
    :required ["template_uri"]}})

(def list_standard_templates_spec
  {:type "function"
   :function
   {:name "list_standard_templates"
    :description (str "List the templates defined by a data standard. The template URIs are returned and 'get_template_meta' can be used to get further info about a specific template.")
    :parameters
    {:type "object"
     :properties
     {:standard_uri
      {:type "string"
       :enum ["<http://syn.org/gdc>"]
       :description "The data standard URI, of the format '<http://syn.org/{data_standard}>', i.e. '<http://syn.org/gdc>'. Currently, only GDC standard is supported."}}}
    :required ["standard_uri"]}})

(def read_file_spec
  {:type "function"
   :function
   {:name "read_file"
    :description "Read text content accessible as a local file or via a URL. Files like Excel are *not* supported. Files larger than 50kb will not be read."
    :parameters
    {:type "object"
     :properties
     {:file {:type "string" 
             :description "Local file path such as 'input/sample.csv' and 'examples/code.js', or URL such as 'https://raw.githubusercontent.com/.../data/sample-csv/organizations.csv'"}
      }}
    :required ["file"]}})

(def summarize_file_spec
  {:type "function"
   :function
   {:name "summarize_file"
    :description "Get summary of the data within a csv file, such as columns present, unique values and value ranges. This can handle larger files."
    :parameters
    {:type "object"
     :properties
     {:file {:type "string"
             :description "Local file path such as 'input/sample.csv'."}}}
    :required ["file"]}})

(def submit_data_spec
  {:type "function"
   :function
   {:name "submit_data"
    :description "CSV data or JSON defining the mapping/transform to be implemented (if so, conforms to a JSON schema)."
    :parameters
    {:type "object"
     :properties
     {:data
      {:type "string"
       :description "Data to be written to the file."}
      :filename
      {:type "string"
       :description "File name, including the file extension."}}}
    :required ["data" "filename"] }})

(def tools
  [find_matching_attribute_spec
   get_attribute_meta_spec
   get_template_meta_spec
   list_standard_templates_spec
   read_file_spec
   summarize_file_spec
   submit_data_spec
   ])

(def anthropic-tools (chat/convert-tools-for-anthropic tools true))

;;;;;;;;;;;;;;;;;;;;;;
;; Tool call wrappers
;;;;;;;;;;;;;;;;;;;;;;

(defn wrap-find-matching-attribute
  [{:keys [attribute_uri]}]
  (let [result (arachne/get-same-property attribute_uri)]
    (mu/log ::find-matching-attribute :param attribute_uri) 
    (if (or (nil? result) (empty? result))
      {:result "No known matches were found."
       :type :success}
      {:result (str result)
       :type :success})))

(defn wrap-get-attribute-meta 
  [{:keys [attribute_uri]}]
  (let [result (arachne/describe-uri attribute_uri)]
    (mu/log ::get-attribute-meta  :param attribute_uri)
    (if (or (nil? result) (empty? result))
      {:result "No result."
       :type :success}
      {:result (str result) 
       :type :success})))

(defn wrap-get-template-meta
  [{:keys [template_uri]}]
  (let [result (arachne/describe-template-columns template_uri)]
    (if (or (nil? result) (empty? result))
        {:result "No result."
         :type :success}
        {:result (str result)
         :type :success})))

(defn wrap-list-standard-templates
  [{:keys [standard_uri]}] 
  (let [result (arachne/list-templates standard_uri)]
    (mu/log ::list-standard-templates  :param standard_uri)
    (if (or (nil? result) (empty? result))
        {:result "No result."
         :type :success}
        {:result (str result)
         :type :success})))

(defn wrap-read-file 
  [{:keys [file]}]
  (let [file-obj (java.io.File. file)
         size-in-kb (/ (.length file-obj) 1024.0)]
    (mu/log ::read-file  :filename file)
     (if (< size-in-kb 10)
       {:result (slurp file)
        :type :success}
       {:result "File is too large."
        :type :error})))

(defn wrap-submit-data 
  [{:keys [data filename]}]
  (let [file (spit filename data)]
    (mu/log ::submit-data :filename filename :message data)
    {:result "File stored."
     :type :success}))

(defn wrap-summarize-file
  [{:keys [file]}] 
  (let [result (cu/summarize-manifest file)]
    (mu/log ::summarize-file  :filename file)
    {:result (str result)
     :type :success}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom tool time
;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn with-next-tool-call
  "Applies logic for chaining certain tool calls. NOTE: Stub, not used."
  [tool-result]
  tool-result)

(defn tool-time 
  [tool-call]
  (let [call-fn (get-in tool-call [:function :name])
        args    (json/parse-string (get-in tool-call [:function :arguments]) true)]
    (try
      (let [result (case call-fn
                     "find_matching_attribute"         (wrap-find-matching-attribute args)
                     "get_attribute_meta"              (wrap-get-attribute-meta args)
                     "get_template_meta"               (wrap-get-template-meta args)
                     "list_standard_templates"         (wrap-list-standard-templates args)
                     "read_file"                       (wrap-read-file args)
                     "summarize_file"                  (wrap-summarize-file args)
                     "submit_data"                (wrap-submit-data args)
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
  "You are a data management agent who specializes in reviewing diverse data templates based on the CCDI standard and translating them to a desired common standard called **GDC**. "
  "Your most common workflow consists of obtaining from the user the paths to one or more CSV templates of entity data that they need to transform to a target set of templates in the GDC standard. "
  "For each CSV file with records of some entity type, you can use the 'read_file' tool to extract and see the entity data records. " 
  ;; "Then you can use the tool load_into_working_graph to put the entity data into an OLAP knowledge graph, which is similar to using a data warehouse for data transformations. " 
  "Given the input, you can query for information about matching target attributes and target templates to better understand specifications for the transformation. " 
  "For example, given a CSV file called 'sample.csv' that may contain column 'sample_attribute_1', you can see whether 'sample_attribute_1' matches an attribute in the GDC standard, which GDC template it's used in, and acceptable values for the GDC version of the attribute. "
  "You may retrieve the list of potential target templates in the GDC standard. Note that the inputs may not have a 1:1 match to the GDC templates, so not all GDC templates are output targets, only the relevant ones. "
  "Once you have determined which GDC templates to output and how to translate the data sufficiently, either submit the csv data directly or use the mapping/transform specification schema (below), whichever is better. The output must contain/specify all columns in the target template!\n"
  (slurp "resources/map_spec.json")
 ))

(def openai-messages (atom [{:role "system" :content role}]))

(def anthropic-messages (atom []))

(def meta (atom {:system role}))

(def OpenAIArachneAgent 
  (chat/->OpenAIProvider "gpt-4o"
                   openai-messages
                   tools 
                   tool-time
                   meta))

(def AnthropicArachneAgent
  (chat/->AnthropicProvider "claude-3-5-sonnet-20241022" ;;"claude-3-7-sonnet-latest"  
                            anthropic-messages
                            anthropic-tools
                            anthropic-tool-time
                            meta))

(defn -main [] 
  (setup)

  (let [agent (if (= (@u :model-provider) "")
                OpenAIArachneAgent 
                AnthropicArachneAgent)]
    
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
