(ns accent.prompts
  (:require [accent.registry :as registry]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io]))

;; =============================================================================
;; Dataset curation prompts
;; =============================================================================

;; File-level curation 

(defn prep-dataset-manifest-handler
  "Handle dynamic prompt for the dataset file manifest workflow"
  [{:keys [folder_id data_coordinating_center]}] 
  (let [prod "https://raw.githubusercontent.com/Sage-Bionetworks/data_curator_config/refs/heads/prod/"
        config-url (str prod data_coordinating_center "/dca_config.json")] 
    {:messages [{:role "user"
               :content {:type "text"
                         :text (str "Help me create metadata for files in dataset " folder_id " in Synapse. "
                                    "For context, I know that my data coordination center is " data_coordinating_center ", which uses the specific configuration below. Help me understand the parameters as well if needed.")}}
              {:role "user"
               :content {:type "resource"
                         :resource {:uri config-url
                                    :text (slurp config-url)}}}
              {:role "assistant"
               :content {:type "text"
                         :text (str "The first step is to generate a metadata manifest for `folder_id` " folder_id ", assuming this is a qualifying Synapse folder. " 
                                    "I will refer to the DCC-specific configuration for parameters like `asset_view`. "
                                    "To apply the `data_type` parameter for this dataset, I will reference the data types/templates defined in the DCC data model. " 
                                    "Looking at the dataset, if its type is not clear, my protocol suggests that I try to confirm the type by interviewing you about the data or sampling some files (with your permission and if I have the tools available). "
                                    "Occasionally, the data model lacks coverage and doesn't define the appropriate template, in which case it's best to create an issue instead of using an incorrect template. "
                                    "A possible issue is when files within the dataset are not homogenous, since mixed data types are not supported; the files would have to be reorganized into separate dataset folders first. "
                                    "I will not proceed if I am not confident about the manifest I can generate for you. " 
                                    "Let me attempt this approach now.")}}]}))

(registry/defprompt :prep-dataset-manifest
  "Workflow for creating metadata for Synapse dataset files according a specified standard. Workflow may also be called annotation or curation."
  [{:name "folder_id"
    :description "Synapse id of the dataset folder" 
    :required true} 
   {:name "data_coordinating_center" 
    :enum ["ADKP" "AMP-AIM" "BTC" "CB" "EL" "GF" "HTAN" "HTAN2" "NF-OSI" "VEOIBD"] 
    :description "The data coordinating center (DCC) in which this workflow should be placed in context." 
    :required true}]
  :category #{:synapse :curation}
  :permissions #{:read}
  :handler prep-dataset-manifest-handler)
