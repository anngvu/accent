(ns curate.dataset
  (:gen-class)
  (:require [babashka.http-client :as client]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            )
  (:import [org.sagebionetworks.client SynapseClient SynapseClientImpl]
           [org.sagebionetworks.client.exceptions SynapseException SynapseResultNotReadyException]
           [org.sagebionetworks.repo.model.table Query QueryBundleRequest QueryResult QueryResultBundle Row RowSet]
           [org.sagebionetworks.repo.model Project Folder AccessControlList ACCESS_TYPE Project RestrictionInformationRequest RestrictionInformationResponse RestrictableObjectType UserProfile]
           [org.sagebionetworks.repo.model.file FileHandleAssociation FileHandleAssociateType]
           [java.io File]))

;;;;;;;;;;;;;;;;;;;;;;;
;; Defs
;; ;;;;;;;;;;;;;;;;;;;;


(defonce syn (atom nil))

; https://github.com/Sage-Bionetworks/Synapse-Repository-Services/blob/9096fc71a7981ee7a19d507a7838acdadfadc07d/lib/models/src/main/java/org/sagebionetworks/repo/model/AuthorizationConstants.java#L15

(def authenticated-users 273948)

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


(defn s-quote [s] (str "'" s "'"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Synapse API to retrieve data for curation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn new-syn
  "Create SynapseClient instance using bearer token."
  [bearer-token]
  (let [client (SynapseClientImpl.)]
    (.setBearerAuthorizationToken client bearer-token)
    (.setRepositoryEndpoint client "https://repo-prod.prod.sagebase.org/repo/v1")
    (.setAuthEndpoint client "https://repo-prod.prod.sagebase.org/auth/v1")
    (.setFileEndpoint client "https://repo-prod.prod.sagebase.org/file/v1")
    (.setDrsEndpoint client "https://repo-prod.prod.sagebase.org/ga4gh/drs/v1")
    (reset! syn client)))


(defn try-get-async-result
  [^SynapseClient client job-token table-id]
  (try
    (.queryTableEntityBundleAsyncGet client job-token table-id)
    (catch SynapseResultNotReadyException _ nil)))


(defn get-async-result
  "TODO: Enforce retry count."
  [^SynapseClient client job-token table-id]
  (loop [retry-count 0
         backoff-ms 200 ; constant polling instead of exponential backoff
         result (try-get-async-result client job-token table-id)]
    (if result
      (do
        (println "Results retrieved.")
        result)
      (do
        (println "Waiting for Synapse query result...")
        (Thread/sleep backoff-ms)
        (recur (inc retry-count) backoff-ms (try-get-async-result client job-token table-id))))))


(defn get-rows [^RowSet rowset]
  (->>(.getRows rowset)
      (mapv #(.getValues %))))


(defn get-columns [^RowSet rowset]
  (->>(.getHeaders rowset)
      (mapv #(.getName %))))


(defn get-column-types [^RowSet rowset]
  (->>(.getHeaders rowset)
      (mapv #(.getColumnType %))
      (mapv str)))


(defn query-table
   [^SynapseClient client table-id sql]
  (let [offset 0
        limit 1000
        part-mask 1
        job-token (.queryTableEntityBundleAsyncStart client sql offset limit part-mask table-id)]
    (loop [token job-token
           rows []]
      (let [bundle (get-async-result client token table-id)
            query-result (.getQueryResult bundle)
            rowset (.getQueryResults query-result)
            next-page-token (.getNextPageToken query-result)]
        (if next-page-token
          (recur next-page-token (into rows (get-rows rowset)))
          {:rows (into rows (get-rows rowset))
           :cols (get-columns rowset)
           :coltypes (get-column-types rowset)})))))


(defn get-table-column-models
  "For now return just names of columns configured for a table"
  [^SynapseClient client table-id]
  (->>(.getColumnModelsForTableEntity client table-id)
      (mapv #(.getName %))))


(defn get-restriction-level
  "Get an ENTITY's restriction level (OPEN|RESTRICTED_BY_TERMS_OF_USE|CONTROLLED_BY_ACT)"
  [^SynapseClient client subject-id]
  (let [request (doto (RestrictionInformationRequest.)
                  (.setObjectId subject-id)
                  (.setRestrictableObjectType RestrictableObjectType/ENTITY))]
    (str (.getRestrictionLevel (.getRestrictionInformation client request)))))


(defn get-acl
  "ACL may not exist on entity directly so must always first get benefactor id."
  [^SynapseClient client id]
  (let [benefactor (.getId (.getEntityBenefactor client id))]
    (.getACL client benefactor)))


(defn scope-files 
  "Uses an asset-view to get a list of file ids in a scope (scope~folder of contentType=dataset)."
  [^SynapseClient client scope asset-view]
  (let [sql (str/join " " ["SELECT id FROM" asset-view "WHERE parentId='" scope "' and type='file'"])]
    (query-table client asset-view sql)))


(defn scope-manifest
  "Specifically scope out id for manifest file using expected name pattern"
  [^SynapseClient client scope asset-view]
  (let [sql (str/join " " ["SELECT id FROM" asset-view "WHERE parentId='" scope "' and name like 'synapse_storage_manifest%'"])]
    (->(query-table client asset-view sql)
       (:rows)
       (ffirst))))


(defn scope-dataset-folders
  "Uses an asset-view to get dataset folders based on contentType=dataset in a project"
  [^SynapseClient client project asset-view]
  (let [sql (str/join " " ["SELECT id,name FROM" asset-view "WHERE type='folder' and contentType='dataset' and projectId='" project "'"])]
    (->>(query-table client asset-view sql)
        (:rows))))


(defn format-item [[id name]]
  (str "{ id: '" id "', name: '" name "'}"))

(defn format-data [data]
  (str "["
       (str/join ", " (mapv format-item data))
       "]"))

(defn scope-dataset-folders-report
  "Wrapper for user-friendly report of scope-dataset-folders"
  [^SynapseClient client project asset-view]
  (let [result (scope-dataset-folders client project asset-view)]
    (if (count result)
      (str "Multiple potential datasets found for project. Candidates for further selection: " (format-data result))
      "No potential datasets found in project scope.")))


(defn get-file-as-creator
  [^SynapseClient client id]
  (let [file-handle-id (.getDataFileHandleId (.getEntityById client id))
        temp-url (.getFileHandleTemporaryUrl client file-handle-id)]
    (client/get temp-url)))


(defn download-file
  [^SynapseClient client id output-path]
  (let [file-handle-id (.getDataFileHandleId (.getEntityById client id))
        file-handle-assoc (doto (FileHandleAssociation.)
               (.setAssociateObjectId id)
               (.setAssociateObjectType FileHandleAssociateType/FileEntity)
               (.setFileHandleId file-handle-id))]
    (.downloadFile client file-handle-assoc (File. output-path))
    output-path))


(defn get-stored-manifest
  "Get data from a manifest file associated with a scope and stored in Synapse directly within the scope.
  Alternative usage to consider:
  If the manifest is stored in an alternate location (not directly within the sccope), use `get-data-url`.
  If no manifest file stored at all, use annotations (assuming annotations were applied).
  If the manifest is stored within scope, but there are ACT-controlled restrictions."
  [^SynapseClient client scope asset-view]
  (if-let [manifest-id (scope-manifest client scope asset-view)]
    (download-file client manifest-id (str scope "-manifest.csv"))
    "Manifest not automatically found"))


(defn re-manifest
  "Remanifest from annotations with explicit input regarding the expected manifest template.
  TODO: More advanced implementation - if the expected template schema is not provided,
  use Component and data model lookup to generate the manifest representation."
  []
  "TODO")


(defn public-release?
  "True if has been widely released for download to authenticated Synapse users, or nil."
  [^AccessControlList acl]
  (some #(and (= (.getPrincipalId %) authenticated-users)
              (.contains (.getAccessType %) ACCESS_TYPE/DOWNLOAD))
        (.getResourceAccess acl)))


(defn has-AR?
  "An entity that has a get-restriction-level result of not OPEN."
  [^SynapseClient client id]
  (not= "OPEN" (get-restriction-level client id)))


(defn get-parent-project-id
  "Get parent project for some entity within that project."
  [^SynapseClient client id]
  (let [entity (.getEntityById client id)]
    (if (instance? Project entity)
      id
      (recur client (.getParentId entity)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Follow-up processing
;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn read-csv-file [file-path]
  (with-open [reader (io/reader file-path)]
    (doall
     (csv/read-csv reader))))


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


(defn summarize-manifest
  "Read a csv manifest, analyze and summarize it column-by-column."
  [file-path]
  (let [manf (read-csv-file file-path)
        headers (first manf)
        columns (apply map vector (rest manf))]
    (zipmap headers (map summarize-column columns))))


(defn label-access 
  "Essentially a mapping that interprets access level + settings to schema-specific labels.
   Until there is AR bypass, ACT control takes utmost precedence and controls access even for contributors/admins.
   If there is no ACT control, *then* control is determined by checking ACL for public group download.
   This why cond checks in the order below."
  [^SynapseClient client id]
  (let [arl (get-restriction-level client id)]
    (cond
      (= "CONTROLLED_BY_ACT" arl) "CONTROLLED ACCESS"
      (not (public-release? (get-acl client id))) "PRIVATE ACCESS"
      :else "PUBLIC ACCESS")))


(defn get-contributor
  "The most parsimonious approach for designating contributors
  is to use all unique people who uploaded files part of the dataset.
  This is the base approach; DCCs can also plug in a unique but non-translatable method.
  Ultimately, shouldn't be expected to be initialized with perfect values and
  should be pointed out as one of the more high-priority items for human review."
  [^SynapseClient client scope asset-view]
  (let [sql (str/join " " ["SELECT distinct createdBy, modifiedBy FROM"
                           asset-view
                           "WHERE parentId="
                           (s-quote scope)])]
    (first (:rows (query-table client asset-view sql)))))


(defn get-user-name
  ([client]
   (let [self (.getMyProfile client)]
     (str (.getFirstName self) " " (.getLastName self))))
  ([^SynapseClient client user]
   (let [user (.getUserProfile client user)]
     (str (.getFirstName user) " " (.getLastName user)))))


(defn derive-from-manifest
  "Derive metadata from manifest metadata"
  [file-path dataset-props]
  (->(summarize-manifest file-path)
     (select-keys dataset-props)
     (update-vals :unique-values)))


(defn derive-from-system
  "Derive metadata from some default system metadata, e.g. createdBy, modifiedBy."
  [^SynapseClient client scope asset-view]
  {:studyId (get-parent-project-id client scope)
   :creator (get-user-name client)
   :contributor (mapv #(get-user-name client %) (get-contributor client scope asset-view)) ;; alternatively, keep as user ids
   :accessType (label-access client scope)
   })


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
;; Main workflows
;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn curate-dataset-folder
  "Implements a deterministic first pass over a dataset folder
  to compile custom and system meta, then returning results for AI enhancement."
  [^SynapseClient client scope asset-view dataset-props]
  (let [m-file (get-stored-manifest client scope asset-view)
        m' (derive-from-manifest m-file dataset-props)
        s' (derive-from-system client scope asset-view)]
    (merge m' s')))


(defn curate-dataset
  "Currently what's expected in workflow is limited to marked folders,
  even if conceptually it is valid for the input to be a single file,
  a single table, an entire project with various entities, etc."
  [^SynapseClient client scope asset-view dataset-props]
   (let [entity (.getEntityById client scope)]
     (cond
       (instance? Project entity) { :type :redirect :result (scope-dataset-folders-report client scope asset-view) }
       (instance? Folder entity) { :type :success :result (curate-dataset-folder client scope asset-view dataset-props) }
       :else { :type :error :result "Curation workflow requires given scope to be a Project or a Folder."})))
