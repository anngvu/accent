(ns accent.chat
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [babashka.http-client :as client]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [org.httpkit.server :as httpkit]
            [com.brunobonacci.mulog :as mu]))

(defprotocol AIProviderOps
  (parse-response [this resp] [this resp clients] "Handle AI provider response")
  (prompt-ai [this content] [this content tool-choice] "Send prompt to AI provider")
  (add-tool-result [this tool-calls] [this tool-calls clients] "Add tool result to response")
  (get-last-text [this] "Get last text in message history"))

(defprotocol AIProviderStreamOps
  (stream-response [this message tool-choice clients] "Handle streaming AI provider response"))

;;;;;;;;;;;;;;;;;;;;;;
;; Utils
;;;;;;;;;;;;;;;;;;;;;;

(defn as-user-message
  "Structure plain text content"
  [content]
  {:role "user"
   :content content})

(defn check-openai-finish-reason
  "Check OpenAI finish reason, adjusting for inconsistencies."
  [resp]
  (let [stated (get-in resp [:choices 0 :finish_reason])
        tool_calls (get-in resp [:choices 0 :message :tool_calls])]
    (if tool_calls "tool_calls" stated)))

(defn as-last-message
  [messages message response]
  (-> (assoc message :last true)
      (assoc :total-tokens (get-in response [:usage :total_tokens]))))

(defn request-openai-completions
  [body & [as]]
  (try
    (-> (client/post "https://api.openai.com/v1/chat/completions"
                     {:headers {"Content-Type"  "application/json"
                                "Authorization" (str "Bearer " (@u :oak))}
                      :body    (json/generate-string body)
                      :as      (or as (if (@u :stream) :stream :string))
                      :timeout 25000}))
    (catch Exception e 
      {:error   true
       :message (str (.getMessage e))})))

(defn request-anthropic-messages
  [body]
  (try
    (client/post "https://api.anthropic.com/v1/messages"
                 {:headers {"Content-Type"      "application/json"
                            "x-api-key"         (@u :aak)
                            "anthropic-version" "2023-06-01"}
                  :body    (json/generate-string body)
                  :timeout 25000})
    (catch Exception e
      {:error   true
       :message (str (.getMessage e))})))

(def oops
  "Various responses to communicate that the chat is being terminated."
  ["we're over-limit, operations pause suddenly"
   "we're out of prompting space"
   "we're out of prompting scope"
   "we've encountered operational obstacles preventing success"
   "we're out of possible solutions for now"
   "certain obstacles oppose prompt service"
   "onset of prompting stress"])

(defn save-chat
  [messages filename]
  (let [json-str (json/generate-string @messages)]
    (with-open [wr (io/writer filename)]
      (.write wr json-str))))

(defn save-chat-offer
  []
  (print (str "If you would like to save the chat data before the program exits, "
              "please type 'Yes' exactly."))
  (println)
  (flush)
  (when (= "Yes" (read-line))
    (let [filename (str "accent_" ".json")]
      (save-chat "chat.json")
      (flush)
      (println "Saved your chat as" filename "!")))
  (print "Please exit now. Program must be restarted to start a new chat."))

(defn context-stop
  "When context limit reached let user know and present limited option to save chat.
  TODO: ability to start new chat and carry over a summary of last chat,
  requires proactive interception with a reasonable buffer before context limit reached."
  [last-response]
  (println)
  (println "-- NOTIFICATION --")
  (println
   (str "Hey, it looks like " (rand-nth oops)
        ". Context tokens limit has been reached with " (:total-tokens last-response) " tokens."))
  (save-chat-offer))

(defn get-first-message-content 
  "Parses an OpenAI completions response"
  [response-map]
  (when (= 200 (:status response-map))
    (let [body (:body response-map)
          parsed-body (json/parse-string body true)
          first-choice (first (:choices parsed-body))]
      (get-in first-choice [:message :content]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper Fns for Streaming
;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn reduce-tool-call-stream
  "Reducer for streamed tool call given thus-accumulated and latest delta."
  [acc delta]
  (-> (update-in acc [:function :name] #(str (or % "") (get-in delta [:function :name])))
      (update-in [:function :arguments] #(str (or % "") (get-in delta [:function :arguments])))
      (merge (dissoc delta :function))))

(defn update-collected-tool-calls 
  [response-atom tool-call-delta]
  (let [tindex (tool-call-delta :index)]
    (swap! response-atom update-in [:tool_calls tindex]
           (fn [existing]
             (let [existing (or existing {})]
               (reduce-tool-call-stream existing (dissoc tool-call-delta :index)))))))

(defn recreate-parseable-openai-response
  "Helper for making sure the streamed response assembles back to a non-streamed version
  that can be processed downstream with existing code."
  [collected-response]
  (let [response {:choices
                  [{:message
                    {:role "assistant"
                     :content (collected-response :content)}
                    :finish_reason (collected-response :finish_reason)}]}]
    (->>
     (cond->
      response
       (not= [] (collected-response :tool_calls)) (assoc-in [:choices 0 :message :tool_calls] (collected-response :tool_calls)))
     (json/generate-string)
     (hash-map :body))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OpenAIProvider Definition
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype OpenAIProvider [model messages tools tool-time]
  AIProviderOps
  (parse-response [this resp] (parse-response this resp nil))
  (parse-response [this resp clients]
    (if (:error resp)
      (do
        (println "Error occurred:" (:message resp))
        {:role    "system"
         :content (str "An error occurred: " (:message resp))})
      (let [resp          (json/parse-string (:body resp) true)
            msg           (get-in resp [:choices 0 :message])
            tool-calls    (msg :tool_calls)
            finish-reason (check-openai-finish-reason resp)]
        (swap! messages conj msg)
        (case finish-reason
          "length"        (as-last-message messages (peek @messages) resp)
          "tool_calls"    (add-tool-result this tool-calls clients)
          "content_filter" (peek @messages)
          "stop"          (peek @messages)))))
  (prompt-ai [this content] (prompt-ai this content nil)) 
  (prompt-ai [this content tool-choice]
     (let [message (if (string? content)
                     (as-user-message content)
                     content)]
       (swap! messages conj message)
       (let [response (->
                        (cond->
                        {:model model
                         :messages @messages
                         ;;:tools tools
                         :parallel_tool_calls false
                         :stream (@u :stream)}
                         tools (assoc :tools tools) 
                         tool-choice (assoc :tool_choice {:type "function" :function {:name tool-choice}}))
                       (request-openai-completions))]
         (if (:error response)
           {:error true
            :message (:message response)}
           response))))
  (add-tool-result [this tool-calls] (add-tool-result this tool-calls nil))
  (add-tool-result [this tool-calls clients]
    (let [tool-call   (first tool-calls)
          tool-name   (get-in tool-call [:function :name])
          result      (tool-time tool-call)
          forced-tool (result :next-tool-call)
          msg         {:tool_call_id (tool-call :id)
                       :role         "tool"
                       :name         tool-name
                       :content      (result :result)}]
       (if clients
        (do
          (doseq [client @clients]
            (httpkit/send! client (json/generate-string {:type "observation-message" :content (str "(Assistant used " tool-name ")\n")})))
          (stream-response this msg forced-tool clients)) 
         (parse-response this (prompt-ai this msg forced-tool)))))
  (get-last-text [this] "TODO")

  AIProviderStreamOps
  (stream-response [this message tool-choice clients]
    (let [response (prompt-ai this message tool-choice)
          reader (io/reader (:body response))
          collected-response (atom {:content "" :tool_calls []})]
      (doseq [line (line-seq reader)]
        (when (not (str/blank? line))
          (when (str/starts-with? line "data: ")
            (let [data (subs line 6)] ;; Remove "data: "
              ;;(println data) ;; inspect stream chunks
              (if (= data "[DONE]")
                (do
                  (when clients
                    (doseq [client @clients] (httpkit/send! client (json/generate-string {:type "assistant-end-message" :content "\n"}))))
                  (parse-response this (recreate-parseable-openai-response @collected-response) clients))
                (let [parsed (json/parse-string data true)
                      role (get-in parsed [:choices 0 :delta :role])
                      content (get-in parsed [:choices 0 :delta :content])
                      finish_reason (get-in parsed [:choices 0 :finish_reason])
                      tool_calls (get-in parsed [:choices 0 :delta :tool_calls 0])]
                  (when finish_reason
                    (swap! collected-response assoc :finish_reason finish_reason))
                  (when tool_calls
                    (update-collected-tool-calls collected-response tool_calls))
                  (when content
                    (when clients (doseq [client @clients] 
                                    (httpkit/send! client 
                                                   (json/generate-string 
                                                    {:type (if role "assistant-start-message" "assistant-message") 
                                                     :content content}))))
                    (swap! collected-response update :content str content)))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; AnthropicProvider Definition
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype AnthropicProvider [model messages tools tool-time]
  
  AIProviderOps
  (parse-response [this resp] (parse-response this resp nil))
  (parse-response [this resp clients]
    (if (:error resp)
      (do
        (println "Error occurred:" (:error resp))
        {:role    "system"
         :content (str "An error occurred: " (:error resp))})
      (let [resp       (json/parse-string (:body resp) true)
            content    (:content resp)
            msg        {:role "assistant" :content content}
            stop-reason (:stop_reason resp)
            tool-use (->>(filter #(= "tool_use" (:type %)) content)(first))]
        (swap! messages conj msg)
        (case stop-reason
          "max_tokens"    (as-last-message messages (get-last-text this) resp)
          "tool_use"      (add-tool-result this tool-use)
          "stop_sequence" (get-last-text this)
          "end_turn"      (get-last-text this)))))
  (prompt-ai [this content] (prompt-ai this content nil)) 
  (prompt-ai [this content tool-choice] 
     (let [message (if (string? content) (as-user-message content) content)] 
       (swap! messages conj message) 
       (let [response (-> 
                       (cond->
                       {:model       model
                        :tools       tools
                        :max_tokens  1024
                        ;;:system      nil ;;  (system-prompt) 
                        :messages    @messages
                        :temperature 0
                        :stream      false}
                        tool-choice (assoc :tool_choice {:type "tool" :name tool-choice}))
                      (request-anthropic-messages))]
        (if (:error response)
          {:error   true
           :message (:message response)}
          response))))
   (add-tool-result [this tool-use] (add-tool-result this tool-use nil))
   (add-tool-result [this tool-use clients]
                    (let [result (tool-time tool-use)
                          msg    {:role    "user"
                                  :content [{:type        "tool_result"
                                             :tool_use_id (tool-use :id)
                                             :content     (result :result)}]}]
                      (parse-response this (prompt-ai this msg))))
   (get-last-text [this]
                  (let [msg (peek @messages)]
                    (assoc msg :content (get-in msg [:content 0 :text])))))


;;;;;;;;;;;;;;;;;;;;;
;; Tools
;;;;;;;;;;;;;;;;;;;;;

(defn tool-time
  [tool-call]
  (let [call-fn (get-in tool-call [:function :name])
        args    (json/parse-string (get-in tool-call [:function :arguments]) true)] 
        {:tool   call-fn
         :result "Tools are not implemented in vanilla chat."
         :error  false}))

(defn anthropic-tool-time
  [tool-use]
  (let [tool-call {:id       (:id tool-use)
                   :type     "function"
                   :function {:name      (:name tool-use)
                              :arguments (json/generate-string (:input tool-use))}}]
    (tool-time tool-call)))

(defn convert-tools-for-anthropic
  "Convert OpenAI tools schema to Anthropic tools schema"
  [openai-tools]
  (mapv (fn [tool]
          {:name (get-in tool [:function :name])
           :description (get-in tool [:function :description])
           :input_schema (-> tool
                             (get-in [:function :parameters]))})
        openai-tools))

(def empty-tools nil)

;;;;;;;;;;;;;;;;;;;;;
;; Models
;;;;;;;;;;;;;;;;;;;;;

(def openai-models
  "https://platform.openai.com/docs/models"
  {:default "gpt-4o"
   :models {"gpt-3.5-turbo" {:label "GPT-3.5 Turbo"
                             :context 16385}
            "gpt-4o" {:label "GPT-4o"
                      :context 128000}
            "gpt-4" {:label "GPT-4"
                     :context 128000}
            "gpt-4-turbo-preview" {:label "GPT-4 Turbo"
                                   :context 128000}}})

(def anthropic-models
  "https://docs.anthropic.com/en/docs/about-claude/models"
  {:default "claude-3-5-sonnet-latest"
   :models {"claude-3-5-sonnet-latest" {:label "Claude 3.5 Sonnet"
                                        :context 200000}
            "claude-3-sonnet-20240229" {:label "Claude 3 Sonnet"
                                        :context 200000}}})

;;;;;;;;;;;;;;;;;;;;;
;; Vanilla chat
;;;;;;;;;;;;;;;;;;;;;

(def openai-init-prompt [{:role "system" :content "You are a helpful assistant."}])

(def openai-messages (atom openai-init-prompt))

(def anthropic-messages (atom []))

(def OpenAIChatAgent 
  (OpenAIProvider. "gpt-4o" 
                   openai-messages
                   empty-tools 
                   tool-time))

(def AnthropicChatAgent 
  (AnthropicProvider. "claude-3-5-sonnet-latest" 
                      anthropic-messages
                      empty-tools
                      anthropic-tool-time))

(defn -main []
  (setup)
  (let [provider (if (= (@u :model-provider) "OpenAI") 
                   OpenAIChatAgent
                   AnthropicChatAgent)]
    (println "Chat initialized. Your message:") 
    (loop [prompt (read-line)]
      (let [ai-reply (->> prompt
                         (prompt-ai provider)
                         (parse-response provider))]
        (println "accent:" (ai-reply :content))
        (when-not (:final ai-reply)
          (print "user: ")
          (flush)
          (recur (read-line)))))))
