(ns agents.arachne
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [accent.chat :as agent]
            [accent.tools :as tools]
            [curate.util :as cu]
            [database.arachne :as arachne]
            [cheshire.core :as json]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]))

(setup) 

(mu/start-publisher! {:type :simple-file
                      :filename "/tmp/mulog/events.edn"})

(def arachne-agent-config
  {:name "Arachne Biomedical Data Fabric Agent"
   :provider :anthropic ;; (@u :model-provider)
   :model "claude-3-5-sonnet-20241022"
   :role (str
          "You are a data management agent who specializes in reviewing diverse data templates based on the CCDI standard and translating them to a desired common standard called **GDC**. "
          "Your most common workflow consists of obtaining from the user the paths to one or more CSV templates of entity data that they need to transform to a target set of templates in the GDC standard. "
          "For each CSV file with records of some entity type, you can use the 'read_file' tool to extract and see the entity data records. "
          ;; "Then you can use the tool load_into_working_graph to put the entity data into an OLAP knowledge graph, which is similar to using a data warehouse for data transformations. " 
          "Given the input, you can query for information about matching target attributes and target templates to better understand specifications for the transformation. "
          "For example, given a CSV file called 'sample.csv' that may contain column 'sample_attribute_1', you can see whether 'sample_attribute_1' matches an attribute in the GDC standard, which GDC template it's used in, and acceptable values for the GDC version of the attribute. "
          "You may retrieve the list of potential target templates in the GDC standard. Note that the inputs may not have a 1:1 match to the GDC templates, so not all GDC templates are output targets, only the relevant ones. "
          "Once you have determined which GDC templates to output and how to translate the data sufficiently, either submit the csv data directly or use the mapping/transform specification schema (below), whichever is better. The output must contain/specify all columns in the target template!\n"
          (slurp "resources/map_spec.json"))
   :tools #{:find-matching-attribute :get-attribute-meta :get-template-meta :list-standard-templates :summarize-file}})

(def Arachne (agent/create-agent arachne-agent-config))

(defn -main [] 
  (.addShutdownHook 
   (Runtime/getRuntime) 
   (Thread. (fn [] 
              (try
                ;; (chat/save-messages agent) ;; save based on config 
                (catch Exception e 
                  (mu/log ::shutdown-error 
                             :msg "Error during shutdown" 
                             :exception e))) 
              (mu/log ::shutdown :msg "Goodbye!")))) 
  (agent/chat Arachne))
