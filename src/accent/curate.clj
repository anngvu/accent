(ns accent.curate
  (:gen-class)
  (:require [accent.core :as a]
            [babashka.http-client :as client]
            [cheshire.core :as json]
            [clojure.data.csv :as csv]))


(defn get-scope-ids
  "Get scope ids for a view entity"
  [id]
  (let [url (str "https://repo-prod.prod.sagebase.org/repo/v1/entity/" id)]
    (->> (client/get url {:headers {:Content-type "application/json" :Authorization (str "Bearer " a/synapse-auth-token)}})
         (:scopeIds))))

(defn list-children [id]
  (->(client/post "https://repo-prod.prod.sagebase.org/repo/v1/entity/children"
                  {:headers {:Content-type "application/json" :Authorization (str "Bearer " a/synapse-auth-token)}
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
    (-> (client/get url {:headers {:Content-type "application/json" :Authorization (str "Bearer " a/synapse-auth-token)}})
        (:body)
        (json/parse-string true)
        (get-in [:list 0 :id]))))


(defn get-valid-scope
  "Persist at getting a valid scope, where 'valid' depends on the context.
   In general a scope means a container such as a project, folder, or view,
   but in something like a dataset curation workflow, only a folder is accepted."
  [id type]
  (let [scope ]
    (if (not (nil? input))
      (do
        (reset! a/scope input)
        (b/gum :style [a/scope " located!"] :as :ignored :foreground 212)
      )
      (recur (first (:result (b/gum :input :placeholder "Id given isn't an existing " type)))))))


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

(defn confirm-and-submit
  []
  (if (b/gum :confirm ["Submit this dataset meta?"] :as :bool)
    (print "Submitted")
    (print "Aborted")))


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



(defn curate-dataset-folder
  "Controlled curation flow for dataset folder->dataset entity with complete metadata"
  [id])
