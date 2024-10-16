(ns accent.chat.stream-processing-test
  (:require [clojure.test :refer :all]
            [accent.chat :refer [reduce-tool-call-stream update-collected-tool-calls]]
            [cheshire.core :as json]))


(def stream-data [
  {:id "chatcmpl-AIfKEOnZOPC5ruso6LtmzxZ8NG0LX" :object "chat.completion.chunk" :created 1729012122 :model "gpt-4o-2024-08-06" :system_fingerprint "fp_6b68a8204b" :choices [{:index 0 :delta {:role "assistant" :content nil :tool_calls [{:index 0 :id "call_Kn5letJn5bGXk4xu1Z4vqgw2" :type "function" :function {:name "get_knowledgegraph_schema" :arguments ""}}] :refusal nil} :logprobs nil :finish_reason nil}]}
  {:id "chatcmpl-AIfKEOnZOPC5ruso6LtmzxZ8NG0LX" :object "chat.completion.chunk" :created 1729012122 :model "gpt-4o-2024-08-06" :system_fingerprint "fp_6b68a8204b" :choices [{:index 0 :delta {:tool_calls [{:index 0 :function {:arguments "{\""}}]} :logprobs nil :finish_reason nil}]}
  {:id "chatcmpl-AIfKEOnZOPC5ruso6LtmzxZ8NG0LX" :object "chat.completion.chunk" :created 1729012122 :model "gpt-4o-2024-08-06" :system_fingerprint "fp_6b68a8204b" :choices [{:index 0 :delta {:tool_calls [{:index 0 :function {:arguments "schema"}}]} :logprobs nil :finish_reason nil}]}
  {:id "chatcmpl-AIfKEOnZOPC5ruso6LtmzxZ8NG0LX" :object "chat.completion.chunk" :created 1729012122 :model "gpt-4o-2024-08-06" :system_fingerprint "fp_6b68a8204b" :choices [{:index 0 :delta {:tool_calls [{:index 0 :function {:arguments "_name"}}]} :logprobs nil :finish_reason nil}]}
  {:id "chatcmpl-AIfKEOnZOPC5ruso6LtmzxZ8NG0LX" :object "chat.completion.chunk" :created 1729012122 :model "gpt-4o-2024-08-06" :system_fingerprint "fp_6b68a8204b" :choices [{:index 0 :delta {:tool_calls [{:index 0 :function {:arguments "\":\""}}]} :logprobs nil :finish_reason nil}]}
  {:id "chatcmpl-AIfKEOnZOPC5ruso6LtmzxZ8NG0LX" :object "chat.completion.chunk" :created 1729012122 :model "gpt-4o-2024-08-06" :system_fingerprint "fp_6b68a8204b" :choices [{:index 0 :delta {:tool_calls [{:index 0 :function {:arguments "data"}}]} :logprobs nil :finish_reason nil}]}
  {:id "chatcmpl-AIfKEOnZOPC5ruso6LtmzxZ8NG0LX" :object "chat.completion.chunk" :created 1729012122 :model "gpt-4o-2024-08-06" :system_fingerprint "fp_6b68a8204b" :choices [{:index 0 :delta {:tool_calls [{:index 0 :function {:arguments "-model\"}"}}]} :logprobs nil :finish_reason nil}]}
])


(deftest test-update-collected-tool-calls
  (testing "Handling streamed tool call data"
    (let [accumulated (atom {:tool_calls []})]
      
      (doseq [data stream-data]
        (update-collected-tool-calls accumulated (get-in data [:choices 0 :delta :tool_calls 0])))
      ;;(print @accumulated)
      (let [result @accumulated]
        (is (= "call_Kn5letJn5bGXk4xu1Z4vqgw2" (get-in result [:tool_calls 0 :id])))
        (is (= "function" (get-in result [:tool_calls 0 :type])))
        (is (= "get_knowledgegraph_schema" (get-in result [:tool_calls 0 :function :name])))
        (is (= "{\"schema_name\":\"data-model\"}" (get-in result [:tool_calls 0 :function :arguments])))))))
