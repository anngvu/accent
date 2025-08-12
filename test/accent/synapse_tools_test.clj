(ns accent.synapse-tools-test
  (:require [clojure.test :refer :all]
            [accent.tools :refer :all]
            [cheshire.core :as json])
  (:import [org.sagebionetworks.client SynapseClient]))

;; Mock atom for synapse client
(def mock-syn-atom (atom nil))

;; Mock responses
(def mock-children-response
  {"page" [{"id" "syn123" "name" "file1.txt" "type" "file"}
           {"id" "syn124" "name" "subfolder" "type" "folder"}]
   "totalChildCount" 2})

(def mock-schema-binding-response
  {"entityId" "syn123"
   "schema$id" "https://schema.org/test"
   "enableDerivedAnnotations" true})

(def mock-validation-response
  {"validationResults" {"isValid" true}
   "etag" "abc123"})

(def mock-dataset-response
  {"id" "syn999"
   "name" "Test Dataset" 
   "concreteType" "org.sagebionetworks.repo.model.table.Dataset"})

;; Tests for get-entity-children-page-handler
(deftest test-get-entity-children-page-handler
  (testing "successful children page retrieval"
    (with-redefs [syn mock-syn-atom
                  get-entity-children-page (fn [client parent-id & opts]
                                             mock-children-response)]
      (let [params {:parent_id "syn123"
                   :include_types ["file" "folder"]
                   :include_total_child_count true}
            result (get-entity-children-page-handler params)]
        (is (= "text" (:type result)))
        (is (re-find #"syn123" (:text result)))
        (is (re-find #"totalChildCount" (:text result))))))

  (testing "handles nil optional parameters"
    (with-redefs [syn mock-syn-atom
                  get-entity-children-page (fn [client parent-id & opts]
                                             mock-children-response)]
      (let [params {:parent_id "syn123"}
            result (get-entity-children-page-handler params)]
        (is (= "text" (:type result)))
        (is (string? (:text result))))))

  (testing "handles errors from API"
    (with-redefs [syn mock-syn-atom
                  get-entity-children-page (fn [client parent-id & opts]
                                             {:error "Access denied"})]
      (let [params {:parent_id "syn123"}
            result (get-entity-children-page-handler params)]
        (is (= "text" (:type result)))
        (is (re-find #"Access denied" (:text result)))))))

;; Tests for bind-entity-schema-handler
(deftest test-bind-entity-schema-handler
  (testing "successful schema binding"
    (with-redefs [syn mock-syn-atom
                  bind-entity-schema (fn [client entity-id schema-id & opts]
                                       mock-schema-binding-response)]
      (let [params {:entity_id "syn123"
                   :schema_id "https://schema.org/test"
                   :enable_derived_annotations true}
            result (bind-entity-schema-handler params)]
        (is (= "text" (:type result)))
        (is (re-find #"syn123" (:text result)))
        (is (re-find #"https://schema.org/test" (:text result))))))

  (testing "handles nil enable_derived_annotations"
    (with-redefs [syn mock-syn-atom
                  bind-entity-schema (fn [client entity-id schema-id & opts]
                                       (is (= false (:enable-derived-annotations (apply hash-map opts))))
                                       mock-schema-binding-response)]
      (let [params {:entity_id "syn123"
                   :schema_id "https://schema.org/test"}
            result (bind-entity-schema-handler params)]
        (is (= "text" (:type result))))))

  (testing "handles binding errors"
    (with-redefs [syn mock-syn-atom
                  bind-entity-schema (fn [client entity-id schema-id & opts]
                                       {:error "Schema not found"})]
      (let [params {:entity_id "syn123"
                   :schema_id "invalid-schema"}
            result (bind-entity-schema-handler params)]
        (is (= "text" (:type result)))
        (is (re-find #"Schema not found" (:text result)))))))

;; Tests for validate-entity-schema-handler
(deftest test-validate-entity-schema-handler
  (testing "successful schema validation"
    (with-redefs [syn mock-syn-atom
                  validate-entity-schema (fn [client entity-id]
                                           mock-validation-response)]
      (let [params {:entity_id "syn123"}
            result (validate-entity-schema-handler params)]
        (is (= "text" (:type result)))
        (is (re-find #"validationResults" (:text result)))
        (is (re-find #"abc123" (:text result))))))

  (testing "handles validation errors"
    (with-redefs [syn mock-syn-atom
                  validate-entity-schema (fn [client entity-id]
                                           {:error "Entity not found"})]
      (let [params {:entity_id "syn123"}
            result (validate-entity-schema-handler params)]
        (is (= "text" (:type result)))
        (is (re-find #"Entity not found" (:text result)))))))

;; Tests for create-dataset-handler
(deftest test-create-dataset-handler
  (testing "successful dataset creation with single folder"
    (with-redefs [syn mock-syn-atom
                  create-dataset (fn [client name parent-id folder-ids & opts]
                                   mock-dataset-response)]
      (let [params {:dataset_name "Test Dataset"
                   :parent_id "syn100"
                   :folder_ids ["syn200"]}
            result (create-dataset-handler params)]
        (is (= "text" (:type result)))
        (is (re-find #"syn999" (:text result)))
        (is (re-find #"Test Dataset" (:text result))))))

  (testing "successful dataset creation with multiple folders"
    (with-redefs [syn mock-syn-atom
                  create-dataset (fn [client name parent-id folder-ids & opts]
                                   (is (= ["syn200" "syn201"] folder-ids))
                                   mock-dataset-response)]
      (let [params {:dataset_name "Multi Dataset"
                   :parent_id "syn100"
                   :folder_ids ["syn200" "syn201"]}
            result (create-dataset-handler params)]
        (is (= "text" (:type result))))))

  (testing "handles default version number"
    (with-redefs [syn mock-syn-atom
                  create-dataset (fn [client name parent-id folder-ids & opts]
                                   (is (= 1 (:version-number (apply hash-map opts))))
                                   mock-dataset-response)]
      (let [params {:dataset_name "Version Test"
                   :parent_id "syn100"
                   :folder_ids ["syn200"]}
            result (create-dataset-handler params)]
        (is (= "text" (:type result))))))

  (testing "handles custom version number"
    (with-redefs [syn mock-syn-atom
                  create-dataset (fn [client name parent-id folder-ids & opts]
                                   (is (= 5 (:version-number (apply hash-map opts))))
                                   mock-dataset-response)]
      (let [params {:dataset_name "Version Test"
                   :parent_id "syn100"
                   :folder_ids ["syn200"]
                   :version_number 5}
            result (create-dataset-handler params)]
        (is (= "text" (:type result))))))

  (testing "handles dataset creation errors"
    (with-redefs [syn mock-syn-atom
                  create-dataset (fn [client name parent-id folder-ids & opts]
                                   {:error "Creation failed"})]
      (let [params {:dataset_name "Failed Dataset"
                   :parent_id "syn100"
                   :folder_ids ["syn200"]}
            result (create-dataset-handler params)]
        (is (= "text" (:type result)))
        (is (re-find #"Creation failed" (:text result))))))

  (testing "handles file limit exceeded"
    (with-redefs [syn mock-syn-atom
                  create-dataset (fn [client name parent-id folder-ids & opts]
                                   {:error "Dataset would contain 30001 files, exceeding the 30,000 item limit"})]
      (let [params {:dataset_name "Large Dataset"
                   :parent_id "syn100"
                   :folder_ids ["syn200"]}
            result (create-dataset-handler params)]
        (is (= "text" (:type result)))
        (is (re-find #"30,000 item limit" (:text result)))))))

;; Integration-style tests that test the flow from handler to underlying function
(deftest test-handler-integration
  (testing "get-entity-children-page complete flow"
    (with-redefs [syn mock-syn-atom
                  curate.synapse/http/post (fn [url opts]
                                             (let [body (json/parse-string (:body opts))]
                                               (is (= "syn123" (get body "parentId")))
                                               (is (= ["file"] (get body "includeTypes")))
                                               {:body (json/generate-string mock-children-response)}))]
      (let [params {:parent_id "syn123"
                   :include_types ["file"]}
            result (get-entity-children-page-handler params)]
        (is (= "text" (:type result)))
        (is (re-find #"totalChildCount" (:text result))))))

  (testing "bind-entity-schema complete flow"
    (with-redefs [syn mock-syn-atom
                  curate.synapse/http/put (fn [url opts]
                                            (let [body (json/parse-string (:body opts))]
                                              (is (= "syn123" (get body "entityId")))
                                              (is (= "https://schema.org/test" (get body "schema$id")))
                                              (is (= true (get body "enableDerivedAnnotations")))
                                              {:body (json/generate-string mock-schema-binding-response)}))]
      (let [params {:entity_id "syn123"
                   :schema_id "https://schema.org/test"
                   :enable_derived_annotations true}
            result (bind-entity-schema-handler params)]
        (is (= "text" (:type result)))
        (is (re-find #"enableDerivedAnnotations" (:text result))))))

  (testing "validate-entity-schema complete flow"
    (with-redefs [syn mock-syn-atom
                  curate.synapse/http/get (fn [url opts]
                                            (is (re-find #"/entity/syn123/schema/validation" url))
                                            {:body (json/generate-string mock-validation-response)})]
      (let [params {:entity_id "syn123"}
            result (validate-entity-schema-handler params)]
        (is (= "text" (:type result)))
        (is (re-find #"validationResults" (:text result)))))))

;; Test edge cases and parameter validation
(deftest test-parameter-edge-cases
  (testing "empty folder_ids array"
    (with-redefs [syn mock-syn-atom
                  create-dataset (fn [client name parent-id folder-ids & opts]
                                   (is (= [] folder-ids))
                                   {:error "No folders provided"})]
      (let [params {:dataset_name "Empty Dataset"
                   :parent_id "syn100"
                   :folder_ids []}
            result (create-dataset-handler params)]
        (is (= "text" (:type result)))
        (is (re-find #"No folders provided" (:text result))))))

  (testing "nil parameters handling"
    (with-redefs [syn mock-syn-atom
                  get-entity-children-page (fn [client parent-id & opts]
                                             (is (= "syn123" parent-id))
                                             mock-children-response)]
      (let [params {:parent_id "syn123"
                   :include_types nil
                   :next_page_token nil}
            result (get-entity-children-page-handler params)]
        (is (= "text" (:type result))))))

  (testing "string conversion in results"
    (with-redefs [syn mock-syn-atom
                  validate-entity-schema (fn [client entity-id]
                                           {"complex" {"nested" {"data" true}}})]
      (let [params {:entity_id "syn123"}
            result (validate-entity-schema-handler params)]
        (is (= "text" (:type result)))
        (is (string? (:text result)))
        (is (re-find #"complex" (:text result)))))))