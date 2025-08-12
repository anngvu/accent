(ns curate.synapse-test
  (:require [clojure.test :refer :all]
            [curate.synapse :refer :all]
            [cheshire.core :as json])
  (:import [org.sagebionetworks.client SynapseClient]))

;; Mock SynapseClient for testing
(defn mock-synapse-client 
  [responses]
  (let [response-atom (atom responses)]
    (reify SynapseClient
      (getRepoEndpoint [_] "https://repo-test.org/repo/v1")
      (getAccessToken [_] "mock-token"))))

;; Test data
(def sample-children-response
  {"page" [{"id" "syn123" "name" "file1.txt" "type" "file"}
           {"id" "syn124" "name" "file2.txt" "type" "file"}
           {"id" "syn125" "name" "subfolder" "type" "folder"}]
   "totalChildCount" 3
   "sumFileSizesBytes" 1024})

(def sample-nested-children-response
  {"page" [{"id" "syn126" "name" "nested_file.txt" "type" "file"}]
   "totalChildCount" 1
   "sumFileSizesBytes" 512})

(def sample-schema-binding-response
  {"entityId" "syn123"
   "schema$id" "https://schema.org/test"
   "enableDerivedAnnotations" true})

(def sample-validation-response
  {"validationResults" {"isValid" true}
   "etag" "abc123"})

;; Tests for get-entity-children-page
(deftest test-get-entity-children-page
  (testing "successful children page retrieval"
    (with-redefs [curate.synapse/http/post (fn [url opts]
                                             {:body (json/generate-string sample-children-response)})]
      (let [client (mock-synapse-client {})
            result (get-entity-children-page client "syn123" 
                                           :include-types ["file" "folder"]
                                           :include-total-child-count true)]
        (is (= 3 (get result "totalChildCount")))
        (is (= 3 (count (get result "page"))))
        (is (= "syn123" (get-in result ["page" 0 "id"]))))))

  (testing "handles HTTP errors gracefully"
    (with-redefs [curate.synapse/http/post (fn [url opts]
                                             (throw (Exception. "Network error")))]
      (let [client (mock-synapse-client {})
            result (get-entity-children-page client "syn123")]
        (is (contains? result :error))
        (is (re-find #"Failed to get entity children" (:error result)))))))

;; Tests for bind-entity-schema
(deftest test-bind-entity-schema
  (testing "successful schema binding"
    (with-redefs [curate.synapse/http/put (fn [url opts]
                                            {:body (json/generate-string sample-schema-binding-response)})]
      (let [client (mock-synapse-client {})
            result (bind-entity-schema client "syn123" "https://schema.org/test" 
                                     :enable-derived-annotations true)]
        (is (= "syn123" (get result "entityId")))
        (is (= "https://schema.org/test" (get result "schema$id")))
        (is (= true (get result "enableDerivedAnnotations"))))))

  (testing "handles binding errors"
    (with-redefs [curate.synapse/http/put (fn [url opts]
                                            (throw (Exception. "Schema not found")))]
      (let [client (mock-synapse-client {})
            result (bind-entity-schema client "syn123" "invalid-schema")]
        (is (contains? result :error))
        (is (re-find #"Failed to bind schema" (:error result)))))))

;; Tests for validate-entity-schema
(deftest test-validate-entity-schema
  (testing "successful schema validation"
    (with-redefs [curate.synapse/http/get (fn [url opts]
                                            {:body (json/generate-string sample-validation-response)})]
      (let [client (mock-synapse-client {})
            result (validate-entity-schema client "syn123")]
        (is (= true (get-in result ["validationResults" "isValid"])))
        (is (= "abc123" (get result "etag"))))))

  (testing "handles validation errors"
    (with-redefs [curate.synapse/http/get (fn [url opts]
                                            (throw (Exception. "Entity not found")))]
      (let [client (mock-synapse-client {})
            result (validate-entity-schema client "syn123")]
        (is (contains? result :error))
        (is (re-find #"Failed to get schema validation" (:error result)))))))

;; Tests for collect-files-recursively
(deftest test-collect-files-recursively
  (testing "collects files from single folder"
    (with-redefs [get-entity-children-page (fn [client parent-id & opts]
                                             sample-children-response)]
      (let [client (mock-synapse-client {})
            result (collect-files-recursively client "syn100")]
        (is (= 2 (count result))) ; Only files, not folders
        (is (= "syn123" (:entityId (first result))))
        (is (= 1 (:versionNumber (first result)))))))

  (testing "handles recursive folder traversal"
    (with-redefs [get-entity-children-page (fn [client parent-id & opts]
                                             (if (= parent-id "syn100")
                                               sample-children-response
                                               sample-nested-children-response))]
      (let [client (mock-synapse-client {})
            result (collect-files-recursively client "syn100")]
        (is (= 3 (count result))) ; 2 from root + 1 from subfolder
        (is (every? #(= 1 (:versionNumber %)) result)))))

  (testing "handles errors in file collection"
    (with-redefs [get-entity-children-page (fn [client parent-id & opts]
                                             {:error "Access denied"})]
      (let [client (mock-synapse-client {})
            result (collect-files-recursively client "syn100")]
        (is (contains? result :error))
        (is (= "Access denied" (:error result)))))))

;; Tests for create-dataset
(deftest test-create-dataset
  (testing "creates dataset with single folder"
    (with-redefs [collect-files-recursively (fn [client folder-id & opts]
                                               [{:entityId "syn123" :versionNumber 1}
                                                {:entityId "syn124" :versionNumber 1}])
                  curate.synapse/http/post (fn [url opts]
                                             {:body (json/generate-string 
                                                     {"id" "syn999" "name" "Test Dataset"})})]
      (let [client (mock-synapse-client {})
            result (create-dataset client "Test Dataset" "syn100" "syn200")]
        (is (= "syn999" (get result "id")))
        (is (= "Test Dataset" (get result "name"))))))

  (testing "creates dataset with multiple folders"
    (with-redefs [collect-files-recursively (fn [client folder-id & opts]
                                               (case folder-id
                                                 "syn200" [{:entityId "syn123" :versionNumber 1}]
                                                 "syn201" [{:entityId "syn124" :versionNumber 1}]))
                  curate.synapse/http/post (fn [url opts]
                                             {:body (json/generate-string 
                                                     {"id" "syn999" "name" "Multi Dataset"})})]
      (let [client (mock-synapse-client {})
            result (create-dataset client "Multi Dataset" "syn100" ["syn200" "syn201"])]
        (is (= "syn999" (get result "id"))))))

  (testing "handles file limit exceeded"
    (with-redefs [collect-files-recursively (fn [client folder-id & opts]
                                               (vec (repeat 30001 {:entityId "syn123" :versionNumber 1})))]
      (let [client (mock-synapse-client {})
            result (create-dataset client "Large Dataset" "syn100" "syn200")]
        (is (contains? result :error))
        (is (re-find #"exceeding the 30,000 item limit" (:error result))))))

  (testing "handles collection errors"
    (with-redefs [collect-files-recursively (fn [client folder-id & opts]
                                               {:error "Permission denied"})]
      (let [client (mock-synapse-client {})
            result (create-dataset client "Error Dataset" "syn100" "syn200")]
        (is (contains? result :error))
        (is (= "Permission denied" (:error result))))))

  (testing "handles dataset creation errors"
    (with-redefs [collect-files-recursively (fn [client folder-id & opts]
                                               [{:entityId "syn123" :versionNumber 1}])
                  curate.synapse/http/post (fn [url opts]
                                             (throw (Exception. "Create failed")))]
      (let [client (mock-synapse-client {})
            result (create-dataset client "Failed Dataset" "syn100" "syn200")]
        (is (contains? result :error))
        (is (re-find #"Failed to create dataset" (:error result)))))))

;; Test parameter validation and edge cases
(deftest test-parameter-handling
  (testing "get-entity-children-page with custom parameters"
    (with-redefs [curate.synapse/http/post (fn [url opts]
                                             (let [body (json/parse-string (:body opts))]
                                               (is (= ["file"] (get body "includeTypes")))
                                               (is (= false (get body "includeTotalChildCount")))
                                               {:body (json/generate-string sample-children-response)}))]
      (let [client (mock-synapse-client {})]
        (get-entity-children-page client "syn123" 
                                :include-types ["file"]
                                :include-total-child-count false))))

  (testing "bind-entity-schema with default parameters"
    (with-redefs [curate.synapse/http/put (fn [url opts]
                                            (let [body (json/parse-string (:body opts))]
                                              (is (= false (get body "enableDerivedAnnotations")))
                                              {:body (json/generate-string sample-schema-binding-response)}))]
      (let [client (mock-synapse-client {})]
        (bind-entity-schema client "syn123" "https://schema.org/test"))))

  (testing "collect-files-recursively with custom version"
    (with-redefs [get-entity-children-page (fn [client parent-id & opts]
                                             sample-children-response)]
      (let [client (mock-synapse-client {})
            result (collect-files-recursively client "syn100" :version-number 5)]
        (is (every? #(= 5 (:versionNumber %)) result)))))

  (testing "create-dataset with single folder as string"
    (with-redefs [collect-files-recursively (fn [client folder-id & opts]
                                               [{:entityId "syn123" :versionNumber 1}])
                  curate.synapse/http/post (fn [url opts]
                                             {:body (json/generate-string 
                                                     {"id" "syn999" "name" "Single Folder"})})]
      (let [client (mock-synapse-client {})
            result (create-dataset client "Single Folder" "syn100" "syn200")]
        (is (= "syn999" (get result "id")))))))