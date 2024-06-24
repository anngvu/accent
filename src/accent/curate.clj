(ns accent.curate
  (:gen-class)
  (:require [accent.state :refer [u]]
            [babashka.http-client :as client]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            )
  (:import [org.sagebionetworks.client SynapseClient SynapseClientImpl]
           [org.sagebionetworks.client.exceptions SynapseException SynapseResultNotReadyException]
           [org.sagebionetworks.repo.model.table Query QueryBundleRequest QueryResult QueryResultBundle Row RowSet]
           [org.sagebionetworks.repo.model AccessControlList ACCESS_TYPE RestrictionInformationRequest RestrictionInformationResponse RestrictableObjectType]
           [org.sagebionetworks.repo.model.file FileHandleAssociation FileHandleAssociateType]
           [java.io File]))

;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;; ;;;;;;;;;;;;;;;;;;;;

(def public-principal-id 273949)


;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn is-numeric [s]
  (try
    (Double/parseDouble s)
    true
    (catch Exception _ false)))


(defn manifest-match? [entity] (re-find (re-matcher #"synapse_storage_manifest" (entity :name))))


(defn find-manifest [files] (first (filter manifest-match? files)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Synapse API to retrieve data for curation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn new-syn
  "Default creation of SynapseClient instance using bearer token."
  [bearer-token]
  (let [client (SynapseClientImpl.)]
    (.setBearerAuthorizationToken client bearer-token)
    (.setRepositoryEndpoint client "https://repo-prod.prod.sagebase.org/repo/v1")
    (.setAuthEndpoint client "https://repo-prod.prod.sagebase.org/auth/v1")
    (.setFileEndpoint client "https://repo-prod.prod.sagebase.org/file/v1")
    (.setDrsEndpoint client "https://repo-prod.prod.sagebase.org/ga4gh/drs/v1")
    client))


(defn try-get-async-result
  [^SynapseClient client job-token table-id]
  (try
    (.queryTableEntityBundleAsyncGet client job-token table-id)
    (catch SynapseResultNotReadyException _ nil)))


(defn get-async-result
  [^SynapseClient client job-token table-id]
  (loop [retry-count 0
         backoff-ms 100] ; constant polling instead of exponential backoff
    (when (and (try-get-async-result client job-token table-id) (< retry-count 10))
      (do
        (println "Async job not ready yet. Retrying...")
        (Thread/sleep backoff-ms)
        (recur (inc retry-count) backoff-ms)))))


(defn query-table
  [^SynapseClient client table-id sql]
  (let [offset 0
        limit 1000
        part-mask 1
        job-token (.queryTableEntityBundleAsyncStart client sql offset limit part-mask table-id)]
    (loop [token job-token
           results []]
      (let [bundle (get-async-result client token table-id)
            query-result (.getQueryResult bundle)
            rowset (.getQueryResults query-result)
            next-page-token (.getNextPageToken query-result)]
        (if next-page-token
          (recur next-page-token (into results rowset))
          (into results rowset))))))


(defn get-rows [^RowSet rowset]
  (->> (.getRows rowset)
       (mapv #(.getValues %))))


(defn get-columns [^RowSet rowset]
  (->> (.getHeaders rowset)
       (mapv #(.getName %))))


(defn get-column-types [^RowSet rowset]
  (->> (.getHeaders rowset)
       (mapv #(.getColumnType %))
       (mapv str)))


(defn get-restriction-level
  "Get an ENTITY's restriction level (OPEN|RESTRICTED_BY_TERMS_OF_USE|CONTROLLED_BY_ACT)"
  [client subject-id]
  (let [request (doto (RestrictionInformationRequest.)
                  (.setObjectId subject-id)
                  (.setRestrictableObjectType RestrictableObjectType/ENTITY))]
    (str (.getRestrictionLevel (.getRestrictionInformation client request)))))


(defn get-acl
  "ACL may not exist on entity directly so must first get benefactor id."
  [client id]
  (let [benefactor (.getId (.getEntityBenefactor client id))]
    (.getACL client benefactor)))


(defn scope-files 
  "Uses an asset-view to get a list of file ids in a scope (presumably a folder of contentType=dataset)."
  [client scope asset-view]
  (let [sql (str/join " " ["SELECT id FROM" asset-view "WHERE parentId='" scope "' and type='file'"])]
    (query-table client asset-view sql)))


(defn get-file-as-creator
  [client id]
  (let [file-handle-id (.getDataFileHandleId (.getEntityById client id))
        temp-url (.getFileHandleTemporaryUrl client file-handle-id)]
    (client/get temp-url)))


(defn download-file
  [client id destination-path]
  (let [file-handle-id (.getDataFileHandleId (.getEntityById client id))
        file-handle-assoc (doto (FileHandleAssociation.)
               (.setAssociateObjectId id)
               (.setAssociateObjectType FileHandleAssociateType/FileEntity)
               (.setFileHandleId file-handle-id))]
    (.downloadFile client file-handle-assoc (File. destination-path))))


(defn get-stored-manifest
  "Get data from a manifest file associated with a scope and stored in Synapse directly within the scope.
  Alternative usage to consider:
  If the manifest is stored in an alternate location (not directly within the sccope), use `get-data-url`.
  If no manifest file stored at all, use annotations (assuming annotations were applied).
  If the manifest is stored within scope, but there are ACT-controlled restrictions."
  [client scope asset-view]
  (let [response (scope-files client scope asset-view)
        manifest-id (find-manifest response)]
    (if manifest-id
      (download-file client manifest-id (str scope ".csv"))
      "Manifest not automatically found")))


(defn re-manifest
  "Remanifest from annotations with explicit input regarding the expected manifest template.
  TODO: More advanced implementation - if the expected template schema is not provided,
  use Component and data model lookup to generate the manifest representation."
  []
  "TODO")


(defn public-release?
  "True whether entity has been released for download for signed-in Synapse users, or nil."
  [acl]
  (some #(and (= (.getPrincipalId %) public-principal-id)
              (.contains (.getAccessType %) ACCESS_TYPE/DOWNLOAD))
        (.getResourceAccess acl)))


(defn has-AR?
  "An entity that has a get-restriction-level result of not OPEN."
  [client id]
  (not= "OPEN" (get-restriction-level client id)))


;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Follow-up processing
;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fill-val
  "Look up val for k in summary ref"
  [k ref]
  (if-let [val (get-in ref [k :unique-values])]
    val
    "TBD"))


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


(defn label-access 
  "Essentially a mapping that interprets access level + settings to schema-specific labels.
   Until there is AR bypass, ACT control takes utmost precedence and controls access even for contributors/admins.
   If there is no ACT control, *then* control is determined by checking ACL for public group download.
   This why cond checks in the order below."
  [client id]
  (let [arl (get-restriction-level client id)] 
    (cond
      "CONTROLLED_BY_ACT" "CONTROLLED ACCESS"
      (public? client id) "PRIVATE ACCESS"
      :else "PUBLIC ACCESS")))


(defn get-contributor
  "The most parsimonious approach (with fewest assumptions) for designating contributors
  is to use all unique people who uploaded files part of the dataset.
  However, DCCs may want to derive contributors
  each in their own special and potentially non-portable way
  (see get-contributor-nf).
  Ultimately, this shouldn't be expected to be initialized with perfect values and
  should be pointed out as one of the more high-priority items for human review."
  [scope]
  ["TODO"]
  )


(defn get-contributor-nf
  "NF's alternative approach for assigning contributors: use everyone named as dataLead on the project.
  This could be an incorrect assumption when different individuals were responsible for different
  datasets in a project (e.g. one obtained the sequencing data while another obtained the imaging data),
  and if this is an important distinction the individuals involved (or not) might not much appreciate this method."
  [scope]
  ["TODO"])


(defn source-values
  "Set values mostly using manifest summary, though selected props have special methods."
  [client scope props ref]
  (into {}
        (map (fn [[k v]]
               [k (cond
                    (= "title" k) "TBD"
                    (= "description" k) "TBD"
                    (= "accessType" k) (label-access client scope)
                    (= "creator" k) (str (get-in @u [:profile :firstName]) (get-in @u [:profile :lastName]))
                    (= "contributor" k) (get-contributor scope)
                    :else (fill-val k ref)
                    )])
             props)))


;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UI
;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: move this to a separate file

(defn coerce-to-boolean [input]
  (let [true-variants #{"Y" "y" "yes" "Yes" "YES"}]
    (contains? true-variants input)))


(defn confirm-prompt
  [placeholder]
  (println placeholder)
  (let [response (read-line)]
    (coerce-to-boolean response)))


;;(defn confirm-prompt-tui
;;  [placeholder]
;;  (b/gum :confirm [placeholder] :as :bool))


(defn confirm-submit-meta
  "Get confirmation via some UI"
  []
  (confirm-prompt "Submit this metadata? (Y/n)"))


;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chained
;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn curate-dataset
  "Controlled curation flow for dataset folder->dataset entity with complete metadata"
  [client id]
  "TODO")
