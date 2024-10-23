(ns agents.extraction
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(def curate_dataset_spec
  {:type "function"
   :function
   {:name "curate_dataset"
    :description "Use this to help user curate a dataset on the Synapse platform given a scope id and, optionally, a manifest id."
    :parameters
    {:type "object"
     :properties
     {:scope_id
      {:type "string"
       :description "The scope id, which should have format syn[0-9]+"}
      :manifest_id
      {:type "string"
       :description (str "The manifest id, which should have format syn[0-9]+"
                         "While the manifest can be automatically discovered in most cases,"
                         " when not in the expected location the id should be provided.")}}}
    :required ["scope_id"] }})

(def get_queryable_fields_spec
  {:type "function"
   :function
   {:name "get_queryable_fields"
    :description (str "Use this to confirm the availability of a Synapse table and its queryable fields to help answer user questions. "
                      "Review the present fields to confirm whether the table contains data needed to answer the user question. "
                      "Then construct a query for ask_table or list the fields found and explain why the question cannot be answered.")
    :parameters
    {:type "object"
     :properties
     {:table_id
      {:type "string"
       :description (str "Table id in the format syn[0-9]+")}}}
    :required ["table_id"]}})

(def ask_table_spec
  {:type "function"
   :function
   {:name "ask_table"
    :description (str "Use to query table with SQL to help answer a user question; "
                      "query should include only searchable fields;"
                      "a subset of valid SQL is allowed -- do not include update clauses.")
    :parameters
    {:type "object"
     :properties
     {:table_id {:type "string"
                 :description "Table id in the format syn[0-9]+"}
      :query {:type "string"
              :description "A valid SQL query."}}}}})

(defn custom-synapse-agent 
  "Create a Synapse agent."
  [{:keys [stream] :or {stream false}}] 
  (let [messages [{:role "system"
                  :content "You are a helpful agent that can interact with the Synapse platform to curate and query data assets on the user's behalf."}]]
  (fn  [input & [tool-choice]]
    (let [message (if (string? input) {:role "user" :content input} input)
          messages (conj messages msg)]
      (let [
        (cond->
          {:model (@u :model)
            :messages messages
            :tools [curate_dataset_spec get_queryable_fields_spec ask_table_spec]
            :parallel_tool_calls false
            :stream (@u :stream)}
        tool-choice (assoc :tool_choice {:type "function" :function {:name tool-choice}}))]
      )))))


