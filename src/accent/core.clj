(ns accent.core 
  (:gen-class)
  (:require [babashka.http-client :as client]
            [cheshire.core :as json]  
            [clojure.data.csv :as csv]  
            [clojure.java.io :as io]
            [clojure.string :as s]))

(def api-key (System/getenv "OPENAI_API_KEY"))
(def synapse-auth-token (System/getenv "SYNAPSE_AUTH_TOKEN"))
(def entity-endpoint "https://repo-prod.prod.sagebase.org/repo/v1/entity/")
(def entity-children-endpoint "https://repo-prod.prod.sagebase.org/repo/v1/entity/children")

(def model (atom "gpt-3.5-turbo"))
(def user (atom {}))
(def project (atom {}))


(def tools [
            {:type "function"
             :function
             {:name "curate_dataset"
              :description "Use this to help user curate a datase given the dataset id and, optionally, an id for the related manifest"
              :parameters
              {:type "object"
               :properties {
                 :dataset_id {:type "string" :description "The dataset id, something like 'syn12345678'"}
                 :manifest_id {:type "string" :description "The manifest id, something like 'syn12345678'. In many cases, the manifest can be automatically discovered, but when a manifest is outside of the expected location, it should be explicitly provided."}
               }}
              :required ["dataset_id"] }}
             {:name "ask_database"
              :description (str "Use this to help answer user questions about entities in the different data coordinating centers data models. 
                                 Input should be a valid Datomic query.")
              :parameters
              {:type "object"
               :properties {
                 :query {:type "string" 
                         :description 
                         (str "Datomic query extracting info to answer the user's question." 
                              "Datomic query should be written and returned as plain text using this schema: "
                              "TBD")}}
               :required "query"}}
            ])

(defonce messages (atom [{:role    "system"
                          :content "You are a helpful assistant"}]))


(defn request [prompt]
    (swap! messages conj {:role    "user"
                          :content prompt})
    (client/post "https://api.openai.com/v1/chat/completions"
                 {:headers {"Content-Type" "application/json"
                            "Authorization" (str "Bearer " api-key)}
                  :body    (json/generate-string
                              {:model   @model
                               :messages @messages
                               :tools tools})}))

(defn response [resp]
  (let [resp    (json/parse-string (:body resp) true)
        content (get-in resp [:choices 0 :message :content])
        _       (swap! messages conj {:role    "assistant"
                                      :content content})]
    content))

(defn get-scope-ids [id] 
  (let [url (str entity-endpoint id)] 
    (->> (client/get url {:headers {:Content-type "application/json" :Authorization (str "Bearer " synapse-auth-token)}})
         (:scopeIds))))

(defn list-children [id]
  (->(client/post entity-children-endpoint
                  {:headers {:Content-type "application/json" :Authorization (str "Bearer " synapse-auth-token)}
                   :body (json/encode 
                          {:parentId id
                           :nextPageToken nil
                           :includeTypes ["file"]
                           :sortBy "NAME"
                           :includeTotalChildCount true
                           :includeSumFileSizes true})})
     (:body)
     (json/parse-string true)
     (:page)))

(defn manifest-match? [entity] (re-find (re-matcher #"synapse_storage_manifest" (entity :name))))

(defn find-manifest [files] (first (filter manifest-match? files)))

(defn get-filehandle [id]
  (let [url (str "https://repo-prod.prod.sagebase.org/repo/v1/entity/" id "/filehandles")]
    (-> (client/get url {:headers {:Content-type "application/json" :Authorization (str "Bearer " synapse-auth-token)}})
        (:body)
        (json/parse-string true)
        (get-in [:list 0 :id]))))

(defn get-data-url 
  [syn-id] 
  (let [handle-id (get-filehandle syn-id) 
        url (str "https://repo-prod.prod.sagebase.org/file/v1/file/" handle-id)]
    (client/get url {:headers {:Content-type "application/json" :Authorization (str "Bearer " synapse-auth-token)} 
                     :query-params {"redirect" "true" "fileAssociateType" "FileEntity" "fileAssociateId" syn-id}})))

(defn get-manifest [folder]
  (let [response (list-children folder)
        manifest (find-manifest response)]
    (if manifest (get-data-url (manifest :id)) "Manifest not automatically found")))

(defn is-numeric [s]
  (try
    (Double/parseDouble s)
    true
    (catch Exception _ false)))

(defn summarize-column [column-data]
  (if (every? is-numeric column-data)
    (let [nums (map #(Double/parseDouble %) column-data)]
      {:type "numeric" :min (apply min nums) :max (apply max nums)})
    {:type "ordinal" :unique-values (distinct column-data)}))

(defn summarize-manifest [response]
  (let [manifest (:body response)
        parsed (csv/read-csv manifest)
        headers (first parsed)
        columns (apply map vector (rest parsed))]
    (zipmap headers (map summarize-column columns))))

(defn get-files [id] (list-children id))

(defn get-model
  [url]
  (->(client/get url)
     (:body)
    (json/parse-string false)))


(defn get-benefactor [id]
  (->(client/get (str "https://repo-prod.prod.sagebase.org/repo/v1/entity/" id "/benefactor"))
     (:body)
     (json/parse-string true)
     (:id)
     ))

(defn get-acl [id]
  (let [id (get-benefactor id)]
    (->(client/get (str "https://repo-prod.prod.sagebase.org/repo/v1/entity/" id "/acl"))
       (:body)
       (json/parse-string true))))

(defn public-download?
  "Note: No Open Access for Synapse data, i.e. anonymous download, except with special governance,
  so checking for PUBLIC download is default."
  [acl]
  (some (fn [entry]
        (and (= 273948 (:principalId entry))
             (some #(= "DOWNLOAD" %) (:accessType entry))))
        (:resourceAccess acl)))

(defn has-AR?
  [id]
  (->(client/get (str "https://repo-prod.prod.sagebase.org/repo/v1/entity/" id "/accessRequirement")
                 {:headers {:Content-type "application/json" :Authorization (str "Bearer" synapse-auth-token)}})
     (:body)
     (json/parse-string true)
     (:totalNumberOfResults)))

(defn label-access [id]
  (cond
    (has-AR? id) "CONTROLLED ACCESS"
    (public-download? (get-acl id)) "PUBLIC ACCESS"
    :else "PRIVATE ACCESS"))

(defn fill-val
  "Look up val for k in summary ref"
  [k ref]
  (if-let [val (get-in ref [k :unique-values])]
    val
    "TBD"))

(defn get-user
  "Get user for the current curation session"
  []
  (->(client/get "https://repo-prod.prod.sagebase.org/repo/v1/userProfile"
                 {:headers {:Content-type "application/json" :Authorization (str "Bearer " synapse-auth-token)}})
     (:body)
     (json/parse-string true)))

(defn set-user
  "Set user or prompt for valid token"
  [userdata]
  (reset! user userdata))


(defn get-meta
  [id]
  (->(client/get (str "https://repo-prod.prod.sagebase.org/repo/v1/entity/" id "/annotations2")
                 {:headers {:Content-type "application/json" :Authorization (str "Bearer " synapse-auth-token)}})
     (:body)
     (json/parse-string true)))

(defn get-project [id] (get-meta id))

(defn get-contributor
  "Eventually this may handle two approaches to identify contributors.
   For NF, use everyone named as dataLead on the project;
   though unfortunately this might not reusable by other DCCs
  that don't have the same exact concept/annotation of dataLead.
  Contributors can be all the users who uploaded or modified the files."
  []
  (get-in @project [:dataLead :value]))

(defn source-values
  "Set values mostly using manifest summary, though selected props have special methods."
  [scope props ref]
  (into {}
        (map (fn [[k v]]
               [k (cond
                    (= "title" k) "TBD"
                    (= "description" k) "TBD"
                    (= "accessType" k) (label-access scope)
                    (= "creator" k) (str (@user :firstName) (@user :lastName))
                    (= "contributor" k) (get-contributor)
                    :else (fill-val k ref)
                    )])
             props)))




(defn save-chat
  [filename]
  (let [json-str (json/generate-string @messages)]
    (with-open [wr (io/writer filename)]
      (.write wr json-str))))
