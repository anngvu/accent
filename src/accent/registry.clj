(ns accent.registry
  (:require [cheshire.core :as json]
            [malli.core :as m]
            [malli.error :as me]))

;; Registry atoms - maps names to definitions
(defonce tool-registry (atom {}))
(defonce prompt-registry (atom {}))
(defonce metadata-registry (atom {}))

;; =============================================================================
;; Core Tool Definition Structure with Malli
;; =============================================================================

(def tool-name-schema
  [:keyword {:description "Tool name as keyword"}])

(def description-schema
  [:string {:min 1 :description "Tool description"}])

(def parameters-schema
  [:map {:description "JSON Schema object for tool parameters"}
   [:type [:= "object"]]
   [:properties {:optional true} :map]
   [:required {:optional true} [:vector :string]]])

(def output-schema-schema
  [:map {:description "JSON Schema for tool output"}])

(def handler-schema
  [:fn {:description "Function that handles tool execution"}
   ifn?])

(def category-schema
  [:set {:description "Tool tags for organization"} :keyword])

(def permissions-schema
  [:set {:description "Required permissions"}
   [:enum :read :write :admin]])

(def dependencies-schema
  [:vector {:description "Other tools this depends on"} :keyword])

(def tool-spec-schema
  [:map {:description "Complete tool specification"}
   [:tool-name tool-name-schema]
   [:description description-schema]
   [:parameters parameters-schema]
   [:handler handler-schema]
   [:category {:optional true} category-schema]
   [:permissions {:optional true} permissions-schema]
   [:dependencies {:optional true} dependencies-schema]
   [:output-schema {:optional true} output-schema-schema]])

;; =============================================================================
;; Core Prompt Definition Structure with Malli
;; =============================================================================

(def prompt-name-schema
  [:keyword {:description "Prompt name as keyword"}])

(def prompt-argument-schema
  [:map {:description "Prompt argument specification"}
   [:name :string]
   [:description {:optional true} :string]
   [:required {:optional true} :boolean]])

(def prompt-arguments-schema
  [:vector {:description "List of prompt arguments"}
   prompt-argument-schema])

(def prompt-handler-schema
  [:fn {:description "Function that handles prompt execution, returns messages"}])

(def prompt-spec-schema
  [:map {:description "Complete prompt specification"}
   [:prompt-name prompt-name-schema]
   [:description description-schema]
   [:arguments {:optional true} prompt-arguments-schema]
   [:handler prompt-handler-schema]
   [:category {:optional true} category-schema]
   [:permissions {:optional true} permissions-schema]
   [:dependencies {:optional true} dependencies-schema]])

;; =============================================================================
;; Validation Functions
;; =============================================================================

(defn validate-tool-spec
  "Validate a tool specification using Malli"
  [tool-spec]
  (if (m/validate tool-spec-schema tool-spec)
    tool-spec
    (let [errors (-> tool-spec-schema
                     (m/explain tool-spec)
                     (me/humanize))]
      (throw (ex-info "Invalid tool specification" 
                      {:tool-spec tool-spec
                       :errors errors})))))

(defn validate-prompt-spec
  "Validate a prompt specification using Malli"
  [prompt-spec]
  (if (m/validate prompt-spec-schema prompt-spec)
    prompt-spec
    (let [errors (-> prompt-spec-schema
                     (m/explain prompt-spec)
                     (me/humanize))]
      (throw (ex-info "Invalid prompt specification" 
                      {:prompt-spec prompt-spec
                       :errors errors})))))

;; =============================================================================
;; Registry Functions for Tools
;; =============================================================================

(defn register-tool!
  "Register a tool in the registry"
  [tool-spec]
  (let [validated-spec (validate-tool-spec tool-spec)
        tool-name (:tool-name validated-spec)]
    (swap! tool-registry assoc tool-name validated-spec)
    (swap! metadata-registry assoc tool-name 
           (merge {:type :tool}
                  (select-keys validated-spec [:category :permissions :dependencies])))
    tool-name))

(defn get-tool
  "Get a tool definition by name"
  [tool-name]
  (get @tool-registry tool-name))

(defn list-tools
  "List all registered tools, optionally filtered by category"
  ([]
   (keys @tool-registry))
  ([category]
   (->> @tool-registry
        (filter #(contains? (:category (val %)) category))
        (map key))))

(defn get-tools-by-category
  "Get tools grouped by category"
  []
  (->> @tool-registry
       (mapcat (fn [[k v]] 
                 (map #(vector % k) (:category v))))
       (group-by first)
       (map (fn [[k v]] [k (map second v)]))
       (into {})))

;; =============================================================================
;; Registry Functions for Prompts
;; =============================================================================

(defn register-prompt!
  "Register a prompt in the registry"
  [prompt-spec]
  (let [validated-spec (validate-prompt-spec prompt-spec)
        prompt-name (:prompt-name validated-spec)]
    (swap! prompt-registry assoc prompt-name validated-spec)
    (swap! metadata-registry assoc prompt-name 
           (merge {:type :prompt}
                  (select-keys validated-spec [:category :permissions :dependencies])))
    prompt-name))

(defn get-prompt
  "Get a prompt definition by name"
  [prompt-name]
  (get @prompt-registry prompt-name))

(defn list-prompts
  "List all registered prompts, optionally filtered by category"
  ([]
   (keys @prompt-registry))
  ([category]
   (->> @prompt-registry
        (filter #(= category (:category (val %))))
        (map key))))

(defn get-prompts-by-category
  "Get prompts grouped by category"
  []
  (->> @prompt-registry
       (group-by #(:category (val %)))
       (into {})))

;; =============================================================================
;; Combined Registry Functions
;; =============================================================================

(defn list-all-items
  "List all registered tools and prompts"
  []
  {:tools (keys @tool-registry)
   :prompts (keys @prompt-registry)})

(defn get-all-by-category
  "Get all tools and prompts grouped by category"
  []
  (let [tools-by-cat (get-tools-by-category)
        prompts-by-cat (get-prompts-by-category)]
    (merge-with (fn [tools prompts]
                  {:tools (or tools {})
                   :prompts (or prompts {})})
                (update-vals tools-by-cat #(hash-map :tools %))
                (update-vals prompts-by-cat #(hash-map :prompts %)))))

(defn search-items
  "Search for tools and prompts by name or description"
  [query]
  (let [query-lower (clojure.string/lower-case query)
        matches-query? (fn [item]
                        (or (clojure.string/includes? 
                             (clojure.string/lower-case (name (first item))) 
                             query-lower)
                            (clojure.string/includes? 
                             (clojure.string/lower-case (:description (second item)))
                             query-lower)))
        matching-tools (filter matches-query? @tool-registry)
        matching-prompts (filter matches-query? @prompt-registry)]
    {:tools (into {} matching-tools)
     :prompts (into {} matching-prompts)}))

;; =============================================================================
;; Execution Functions
;; =============================================================================

(defn execute-tool
  "Execute a tool with given arguments.
  NOTE: tool-name expected to be keyword, and converted if given as name."
  [tool-name args]
  (if-let [tool (get-tool (if (keyword? tool-name) tool-name (keyword tool-name)))]
    (try
      ((:handler tool) args)
      (catch Exception e
        {:tool (name tool-name)
         :text (.getMessage e)
         :type "text"
         :isError true}))
    {:tool (name tool-name)
     :text "Tool not found"
     :type "text"
     :isError true}))

(defn execute-prompt
  "Execute a prompt with given arguments"
  [prompt-name args]
  (if-let [prompt (get-prompt prompt-name)]
    (try
      ((:handler prompt) args)
      (catch Exception e
        {:prompt (name prompt-name)
         :text (.getMessage e)
         :type "text"
         :isError true}))
    {:prompt (name prompt-name)
     :text "Prompt not found"
     :type "text"
     :isError true}))

;; =============================================================================
;; Selection Functions
;; =============================================================================

;; TODO

;; =============================================================================
;; Format Conversion for Chat Providers and MCP
;; =============================================================================

(defn tool->openai-spec
  "Convert internal tool spec to OpenAI function calling format"
  [tool-spec]
  {:type "function"
   :function {:name (name (:tool-name tool-spec))
              :description (:description tool-spec)
              :parameters (:parameters tool-spec)}})

(defn tool->anthropic-spec
  "Convert internal tool spec to Anthropic tools format"
  [tool-spec]
  {:name (name (:tool-name tool-spec))
   :description (:description tool-spec)
   :input_schema (:parameters tool-spec)})

(defn tool->mcp-spec
  "Convert internal tool spec to MCP SDK format"
  [tool-spec]
  (let [base {:name (name (:tool-name tool-spec))
              :description (:description tool-spec)
              :inputSchema (:parameters tool-spec)
              :handler (:handler tool-spec)}]
    (if-let [output-schema (:output-schema tool-spec)]
      (assoc base :outputSchema output-schema)
      base)))

(defn prompt->mcp-spec
  "Convert internal prompt spec to MCP SDK format"
  [prompt-spec]
  {:name (name (:prompt-name prompt-spec))
   :description (:description prompt-spec)
   :arguments (or (:arguments prompt-spec) [])
   :handler (:handler prompt-spec)})

(defn tools->openai-format
  "Convert a set of tools in internal registry format to OpenAI format"
  [tools]
  (map tool->openai-spec (vals tools)))

(defn tools->anthropic-format
  "Convert a set of tools in internal registry format to Anthropic format"
  [tools]
  (map tool->anthropic-spec (vals tools)))

(defn tools->mcp-format
  "Convert set of tools in internal registry format to MCP SDK format"
  [tools]
  (map tool->mcp-spec (vals tools)))

(defn prompts->mcp-format
  "Convert a set of prompts to MCP SDK format"
  [prompts]
  (map prompt->mcp-spec (vals prompts)))

(defn tools->openai->anthropic-format
  "Convert OpenAI tools format directly to Anthropic tools format"
  [openai-tools & [cache-breakpoint?]]
  (let [tools-count (count openai-tools)]
    (mapv (fn [tool idx]
            (cond->
             {:name (get-in tool [:function :name])
              :description (get-in tool [:function :description])
              :input_schema (-> tool
                                (get-in [:function :parameters]))}
              (and cache-breakpoint? (= idx (dec tools-count))) (assoc :cache_control {"type" "ephemeral"})))
          openai-tools (range tools-count))))

(defn select-tools
  "Select tools from registry by key, in the desired format"
  [tool-keys provider]
  (let [tools (select-keys @tool-registry tool-keys)]
       (case provider
         :anthropic (tools->anthropic-format tools)
         :openai (tools->openai-format tools))))

;; =============================================================================
;; Universal Dispatchers
;; =============================================================================

(defn create-tool-dispatcher
  "Create a tool dispatcher function for a given tool set"
  [tool-set]
  (fn [tool-call]
    (let [tool-name (keyword (get-in tool-call [:function :name]))
          args (json/parse-string (get-in tool-call [:function :arguments]) true)]
      (if (contains? tool-set tool-name)
        (execute-tool tool-name args)
        {:tool (name tool-name)
         :text "Tool not available"
         :type "text"
         :isError true}))))

(defn create-anthropic-tool-dispatcher
  "Create an Anthropic-compatible tool dispatcher"
  [tool-set]
  (fn [tool-use]
    (let [tool-name (keyword (:name tool-use))
          args (:input tool-use)]
      (if (contains? tool-set tool-name)
        (execute-tool tool-name args)
        {:tool (name tool-name)
         :text "Tool not available"
         :type "text"
         :isError true}))))

(defn create-prompt-dispatcher
  "Create a prompt dispatcher function for a given prompt set"
  [prompt-set]
  (fn [prompt-call]
    (let [prompt-name (keyword (:name prompt-call))
          args (:arguments prompt-call)]
      (if (contains? prompt-set prompt-name)
        (execute-prompt prompt-name args)
        {:prompt (name prompt-name)
         :text "Prompt not available"
         :type "text"
         :error true}))))

;; =============================================================================
;; Helper Macros
;; =============================================================================

(defmacro deftool
  "Macro to define and register a tool in one step.
   TODO: Handler can be either
   - Function body forms: (deftool :my-tool \"...\" {...} :handler (fn [args] (something-with-args)))
   - Function symbol: (deftool :my-tool \"...\" {...} :handler my-function-symbol)"
  [tool-name description parameters & {:keys [category permissions handler] :as opts}]
  (let [category (get opts :category #{})
        permissions (get opts :permissions #{})
        dependencies (get opts :dependencies [])
        handler-fn (get opts :handler)]
    `(register-tool!
      {:tool-name ~tool-name
       :description ~description
       :parameters ~parameters
       :category ~category
       :permissions ~permissions
       :dependencies ~dependencies
       :handler ~handler-fn})))

(defmacro defprompt
  "Macro to define and register a prompt in one step.
   Handler can be either:
   - A function symbol/var: (defprompt :my-prompt \"...\" my-existing-function)
   - Function body forms: (defprompt :my-prompt \"...\" (generate-response args))
   - Explicit :handler key: (defprompt :my-prompt \"...\" :handler my-function)"
  [prompt-name description & args]
  (let [;; Parse keyword arguments and handler body
        {opts true handler-body false} (group-by keyword? args)
        opts-map (apply hash-map opts)
        arguments (get opts-map :arguments [])
        category (get opts-map :category :general)
        permissions (get opts-map :permissions #{})
        dependencies (get opts-map :dependencies [])
        explicit-handler (get opts-map :handler)

        handler-fn (cond
                     ;; If :handler key is provided, use it directly
                     explicit-handler explicit-handler

                     ;; If handler-body is a single symbol, treat it as function reference
                     (and (= 1 (count handler-body))
                          (symbol? (first handler-body)))
                     (first handler-body)

                     ;; Otherwise, wrap the body in a function
                     (seq handler-body)
                     `(fn [~'args] ~@handler-body)

                     ;; No handler provided
                     :else
                     (throw (IllegalArgumentException. "No handler provided for prompt")))]
    `(register-prompt!
      {:prompt-name ~prompt-name
       :description ~description
       :arguments ~arguments
       :category ~category
       :permissions ~permissions
       :dependencies ~dependencies
       :handler ~handler-fn})))

;; =============================================================================
;; Enhanced Validation and Introspection
;; =============================================================================

(defn validate-dependencies
  "Check if all dependencies are satisfied across tools and prompts"
  [tool-set prompt-set]
  (let [available-items (set (concat (keys tool-set) (keys prompt-set)))
        check-deps (fn [items]
                     (->> items
                          (map (fn [[name spec]]
                                 (let [deps (:dependencies spec [])]
                                   [name (filter #(not (contains? available-items %)) deps)])))
                          (filter #(seq (second %)))
                          (into {})))]
    {:missing-tool-dependencies (check-deps tool-set)
     :missing-prompt-dependencies (check-deps prompt-set)}))

(defn health-check-registry
  "Perform basic health checks on the entire registry"
  []
  (let [tools @tool-registry
        prompts @prompt-registry
        all-categories (set (concat (map #(:category (val %)) tools)
                                   (map #(:category (val %)) prompts)))]
    {:total-tools (count tools)
     :total-prompts (count prompts)
     :categories (vec all-categories)
     :tools-by-category (get-tools-by-category)
     :prompts-by-category (get-prompts-by-category)
     :dependency-issues (validate-dependencies tools prompts)
     :validation-errors {:tools (keep (fn [[name spec]]
                                       (when-not (m/validate tool-spec-schema spec)
                                         [name (me/humanize (m/explain tool-spec-schema spec))]))
                                     tools)
                         :prompts (keep (fn [[name spec]]
                                         (when-not (m/validate prompt-spec-schema spec)
                                           [name (me/humanize (m/explain prompt-spec-schema spec))]))
                                       prompts)}}))

;; =============================================================================
;; Schema Utilities
;; =============================================================================

(defn describe-schemas
  "Get human-readable descriptions of all schemas"
  []
  {:tool-spec (m/form tool-spec-schema)
   :prompt-spec (m/form prompt-spec-schema)})

;; =============================================================================
;; Migration Utilities with Validation
;; =============================================================================

(defn import-mcp-tool-with-validation
  "Import an existing MCP tool definition with Malli validation"
  [mcp-tool-def & {:keys [category permissions dependencies]
                   :or {category :general permissions #{} dependencies []}}]
  (let [tool-spec {:tool-name (keyword (:name mcp-tool-def))
                   :description (:description mcp-tool-def)
                   :parameters (:inputSchema mcp-tool-def)
                   :category category
                   :permissions permissions  
                   :dependencies dependencies
                   :handler (:handler mcp-tool-def)}]
    ;; This will validate using Malli schemas
    (register-tool! tool-spec)))

;; =============================================================================
;; Development Helpers
;; =============================================================================

(defn reset-registry!
  "Clear all tools and prompts from the registry (useful for development)"
  []
  (reset! tool-registry {})
  (reset! prompt-registry {})
  (reset! metadata-registry {}))

(defn item-exists?
  "Check if a tool or prompt exists in the registry"
  [item-name]
  (or (contains? @tool-registry item-name)
      (contains? @prompt-registry item-name)))

(defn get-item-info
  "Get detailed information about a tool or prompt"
  [item-name]
  (let [metadata (get @metadata-registry item-name)]
    (case (:type metadata)
      :tool (-> (get-tool item-name)
                (dissoc :handler)
                (assoc :handler-type (type (:handler (get-tool item-name)))))
      :prompt (-> (get-prompt item-name)
                  (dissoc :handler)
                  (assoc :handler-type (type (:handler (get-prompt item-name)))))
      nil)))

(comment
  ;; Example usage
  
  ;; Define tools and prompts
  (deftool :greet
    "Greet a person"
    {:type "object"
     :properties {"name" {:type "string"}}
     :required ["name"]}
    :category #{:social}
    :permissions #{:read}
    
    {:message (str "Hello, " (:name args) "!")})
  
  (defprompt :analyze-code
    "Analyze code for improvements"
    :arguments [{:name "language" :description "Programming language" :required true}
                {:name "code" :description "Code to analyze" :required true}]
    :category :development
    :permissions #{:read}
    
    {:messages [{:role "assistant"
                 :content {:type "text"
                          :text (str "Analyzing " (:language args) " code: " (:code args))}}]})
  
  ;; Check registry health
  (health-check-registry)
  
  ;; Search for items
  (search-items "code")
  
  ;; List everything
  (list-all-items)
)

