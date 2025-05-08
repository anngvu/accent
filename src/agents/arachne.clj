(ns agents.arachne
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [accent.chat :as chat]
            [database.arachne :as arachne]
            [cheshire.core :as json]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]))

;;;;;;;;;;;;;;;;;;;;;
;; Tool defs
;;;;;;;;;;;;;;;;;;;;;

(def find_matching_attribute_spec
  {:type "function"
   :function
   {:name "find_matching_attribute"
    :description (str "Given a source attribute, find a matching attribute in a target attribute set. "
                      "This will return the matching/pairing attribute if it exists. "
                      ;;"If matching attribute found, information like attribute description, etc. is also returned."
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
    :description (str "Get information about an entity template such as its attributes (columns) and their order.")
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

(def read_csv_spec
  {:type "function"
   :function
   {:name "read_csv"
    :description "Read data from a CSV file accessible as a local file or via a URL. Excel files are *not* supported."
    :parameters
    {:type "object"
     :properties
     {:file {:type "string" 
             :description "Local file path such as 'input/sample.csv' or URL such as 'https://raw.githubusercontent.com/codeforamerica/ohana-api/refs/heads/master/data/sample-csv/organizations.csv'"}
      }}
    :required ["file"]}})

(def write_csv_spec
  {:type "function"
   :function
   {:name "write_csv"
    :description "Write data to csv file."
    :parameters
    {:type "object"
     :properties
     {:data
      {:type "string"
       :description "CSV data conforming to a standard template."}
      :filename
      {:type "string"
       :description "Name for the csv file to be written, including the .csv extension."}}}
    :required ["data" "filename"] }})


(def tools
  [find_matching_attribute_spec
   get_attribute_meta_spec
   get_template_meta_spec
   list_standard_templates_spec
   read_csv_spec
   write_csv_spec
   ])

(def anthropic-tools (chat/convert-tools-for-anthropic tools true))

;;;;;;;;;;;;;;;;;;;;;;
;; Tool call wrappers
;;;;;;;;;;;;;;;;;;;;;;

(defn wrap-find-matching-attribute
  [{:keys [attribute_uri]}]
  (let [result (arachne/get-same-property attribute_uri)]
    (if (empty? result)
      {:result "No known matches were found."
       :type :success}
      {:result (str result)
       :type :success})))

(defn wrap-get-attribute-meta 
  [{:keys [attribute_uri]}]
  (let [result (arachne/describe-uri attribute_uri)]
    {:result (str result)
     :type :success}))

(defn wrap-get-template-meta
  [{:keys [template_uri]}]
  (let [result (arachne/describe-template-columns template_uri)]
    {:result (str result)
     :type :success}))

(defn wrap-list-standard-templates
  [{:keys [standard_uri]}]
  (let [result (arachne/list-templates standard_uri)]
    {:result (str result)
     :type :success}))

(defn wrap-read-csv 
  [{:keys [file]}]
  (let [text (slurp file)]
    {:result text 
     :type :success}))

(defn wrap-write-csv 
  [{:keys [data filename]}]
  (let [result (spit filename data)]
    {:result "File written successfully."
     :type :success}))

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
                     "find_matching_attribute"         (wrap-find-matching-attribute args)
                     "get_attribute_meta"              (wrap-get-attribute-meta args)
                     "get_template_meta"               (wrap-get-template-meta args)
                     "list_standard_templates"         (wrap-list-standard-templates args)
                     "read_csv"                        (wrap-read-csv args)
                     "write_csv"                       (wrap-write-csv args)
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
  "You are a data management agent tasked with converting an Excel workbook of entity data into the General Commons (GC) standard format. Follow the structured workflow below carefully, adapting as needed. Ensure clarity, completeness, and faithful mapping to the GC standard at each step."
"Workflow Outline"
" Step 1: Access and Survey the Excel File - Open or connect to the Excel file (it may contain multiple worksheets). Each worksheet represents a different input data template (e.g., distinct entity types). Use the predefined Arachne mapping database to identify which GDC submission template corresponds to each sheet. (Match sheet names or characteristic fields to the GDC template names in the mapping.) Prepare a list of all sheet names and their determined GDC template mappings. This ensures you know which GDC format each sheet’s data should follow before processing."
" Step 2: Process Each Sheet Sequentially - For each worksheet identified in the Excel file, perform the following steps in order:"
" Step 3: Read Data - Use the read_csv (or equivalent sheet-reading function) to load all records from the current sheet. Ensure the data is loaded in a structured form (rows as records, columns as attributes)."
" Step 4: Map Attributes - For each column in the sheet, find the corresponding GC attribute using available query tools and the Arachne mapping. Align each input attribute to the correct field name defined by the GC templates. (If a direct match isn’t found in the mapping, perform a query or search for the closest GC term.)"
" Step 5: Identify Template - Confirm which GC data submission template this sheet’s data belongs to, based on the mapping. In most cases, the sheet maps to a single GC entity template (for example, 'Case', 'Sample', 'Clinical', etc.). If the sheet’s data spans multiple GC templates, handle one template at a time or split the data as appropriate."
" Step 6: Transform Data - Apply any necessary transformations to the data values to meet GC format requirements. For example, convert codes or abbreviations to standardized values, format dates or identifiers as required, and ensure data types and units align with GDC specifications. Perform these transformations before writing the output to avoid post-processing errors."
" Step 7: Write Output - Use the write_csv function to output the transformed data in the structure of the identified GC template. Name or label the output clearly (e.g., using the GDC template name) so it’s distinguishable. The output should be a CSV (or TSV as required by GC) with columns matching the GC template’s expected fields and rows representing each record from the input sheet."
" Step 8: Log Unmatched Columns - If there are any input columns that do not have a corresponding GC attribute (i.e., you couldn’t find them in the mapping or data dictionary), log or record these unmapped columns. This log will help in reviewing any data that couldn’t be translated to the GDC standard. Include details like the sheet name and column header for each unmapped field."
"Final Verification and Reporting: After all sheets are processed, verify that every GC template expected (per the Arachne database) has a corresponding output file or dataset produced. Cross-check your list of sheet-to-template mappings against the outputs you generated. If any GC template from the mapping was not utilized or did not receive data (for example, if a mapped template had no corresponding sheet in the Excel file), report this omission. It could indicate a missing input or a discrepancy in the mapping."
"Ensure that the resulting outputs collectively cover all input data. Output all the mapped CSV or TSV files and also a summary report confirming which sheets were processed into which GC templates. Note any sheets or data that were skipped or could not be mapped, and highlight the presence of any unmapped columns from the previous step. This final report helps stakeholders understand the coverage and any gaps in the data conversion."
"Additional Guidance: Follow the above steps methodically for each sheet, but remain flexible in execution. If an input sheet has an unexpected structure or the mapping is not straightforward, adapt your approach (e.g., by querying additional info from the Arachne database or handling exceptions in data format). Throughout the process, prioritize producing a clear and complete translation of the data. Every piece of input information should either be mapped to the GDC standard or explicitly noted if it cannot be. By adhering to this workflow, you will ensure high fidelity in mapping the Excel data to the GDC templates, with thorough documentation of any issues or deviations."
   ))

(def openai-messages (atom [{:role "system" :content role}]))

(def anthropic-messages (atom []))

(def meta (atom {:system role}))

(def OpenAIArachneAgent 
  (chat/->OpenAIProvider "o3-mini" 
                   openai-messages
                   tools 
                   tool-time
                   meta))

(def AnthropicArachneAgent 
  (chat/->AnthropicProvider "claude-3-7-sonnet-latest" 
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
