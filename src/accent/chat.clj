(ns accent.chat
  (:gen-class)
  (:require [accent.state :refer [setup u]]
            [accent.registry :refer [select-tools create-tool-dispatcher create-anthropic-tool-dispatcher]]
            [babashka.http-client :as client]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [org.httpkit.server :as httpkit]
            [com.brunobonacci.mulog :as mu]))

(defprotocol AIProviderOps
  (parse-response [this resp] [this resp clients] "Handle AI provider response")
  (prompt-ai [this content] [this content tool-choice] "Send prompt to AI provider")
  (add-tool-result [this tool-calls] [this tool-calls clients] "Add tool result to response"))

(defprotocol AIProviderStreamOps
  (stream-response [this message tool-choice clients] "Handle streaming AI provider response with client(s)"))

(defprotocol MessageOps
  (get-last-text [this] "Get last text in message history")
  (save-messages [this] [this file] "Save messages to file")
  (reset-messages [this] "Reset messages to initial message state"))

(defprotocol AgentOps
  (get-model [this] "Get current model")
  (switch-model [this model] "Switch to given model")
  (show-tools [this] "Show the tools agent currently sees")
  (get-context [this] "Get context that agent has access to"))

;;=========================================
;; Utils
;;=========================================

;; (defn compress [data]
;;   (let [baos (java.io.ByteArrayOutputStream.)]
;;     (with-open [gzip (java.util.zip.GZIPOutputStream. baos)]
;;       (.write gzip (.getBytes data "UTF-8")))
;;     (.toByteArray baos)))

(defn as-user-message
  "Structure plain text content"
  [content]
  {:role "user"
   :content content})

(defn get-first-message-content
  "Parses an OpenAI completions response"
  [response-map]
  (when (= 200 (:status response-map))
    (let [body (:body response-map)
          parsed-body (json/parse-string body true)
          first-choice (first (:choices parsed-body))]
      (get-in first-choice [:message :content]))))

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
  [body & [m]]
  (try
    (-> (client/post "https://api.openai.com/v1/chat/completions"
                     {:headers {"Content-Type"  "application/json"
                                "Authorization" (str "Bearer " (@u :oak))}
                      :body    (json/generate-string body)
                      :as      (or (:as m) (if (@u :stream) :stream :string))
                      :connect-timeout (or (:connect-timeout m) 10000)
                      :timeout (or (:timeout m) 25000)}))
    (catch Exception e 
      {:isError   true
       :message (str (.getMessage e))})))

(defn request-anthropic-messages
  [body & [m]]
  (try
    (client/post "https://api.anthropic.com/v1/messages"
                 {:headers {"Content-Type"      "application/json"
                            "x-api-key"         (@u :aak)
                            "anthropic-version" "2023-06-01"}
                  :body    (json/generate-string body)
                  :connect-timeout (or (:connect-timeout m) 10000)
                  :timeout (or (:timeout m) 25000)})
    (catch Exception e
      {:isError   true
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

(defn save-state!
  [state & [filename]]
  (let [json-str (json/generate-string state)
        default-name (str "accent-" (System/currentTimeMillis) ".json")
        fname (or filename default-name)]
    (with-open [wr (io/writer fname)]
      (.write wr json-str))
    fname))

(defn context-stop
  "When context limit reached let user know and present limited option to save chat.
  TODO: ability to start new chat and carry over a summary of last chat,
  requires proactive interception with a reasonable buffer before context limit reached."
  [last-response]
  (println)
  (println "-- NOTIFICATION --")
  (println
   (str "Hey, it looks like " (rand-nth oops)
        ". Context tokens limit has been reached with " (:total-tokens last-response) " tokens.")))

;;=========================================
;; Helper Fns for Streaming
;;=========================================

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

;;=========================================
;; OpenAIProvider Definition
;;=========================================

(deftype OpenAIProvider [^:volatile-mutable model 
                         messages 
                         tools 
                         tool-time 
                         context]
  AIProviderOps
  (parse-response [this resp] (parse-response this resp nil))
  (parse-response [this resp clients]
    (if (:isError resp)
      (do
        (println "Error occurred:" (:message resp))
        {:role    "system"
         :content (str "An error occurred: " (:message resp))})
      (let [resp          (json/parse-string (:body resp) true)
            msg           (get-in resp [:choices 0 :message])
            tool-calls    (msg :tool_calls)
            finish-reason (check-openai-finish-reason resp)]
        (swap! messages conj msg)
        (mu/log ::usage :data (get resp :usage))
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
      (let [tools (if (= "o3-mini") {:tools tools} {:tools tools :parallel_tool_calls false})
            response (->
                      (cond->
                       {:model model
                        :messages @messages
                        :stream (@u :stream)}
                       tools (merge tools)
                       tool-choice (assoc :tool_choice {:type "function" :function {:name tool-choice}}))
                      (request-openai-completions))]
        (if (:isError response)
          {:isError true
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
            (httpkit/send! client (json/generate-string {:type "observation-message" :content (str "(Assistant used " tool-name ")\n")}))
            (when (result :data) (httpkit/send! client (json/generate-string {:type "viz-message" :data (result :data) :dataspec (result :dataspec)}))))

          (stream-response this msg forced-tool clients))
        (parse-response this (prompt-ai this msg forced-tool)))))

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
                    (swap! collected-response update :content str content))))))))))
  
  MessageOps
  (get-last-text [this] "TODO")
  (save-messages [this] (save-state! @messages (str "accent-openai-messages-" (System/currentTimeMillis) ".json")))
  (save-messages [this file] (save-state! @messages file))
  (reset-messages [this] (reset! messages [{:role "system" :content (@context :system)}]))
  
  AgentOps
  (get-model [this] model)
  (switch-model [this new-model] (set! model new-model))
  (show-tools [this] tools)
  (get-context [this] @context))

;;=====================================================
;; Anthropic Provider Def
;;=====================================================

(deftype AnthropicProvider [^:volatile-mutable model 
                            messages 
                            tools 
                            tool-time 
                            context]
  
  AIProviderOps
  (parse-response [this resp] (parse-response this resp nil))
  (parse-response [this resp clients]
    (if (:isError resp)
      (do
        (mu/log ::error :data resp)
        {:role    "system"
         :content (str "An error occurred: " (resp :message))})
      (let [resp       (json/parse-string (:body resp) true)
            content    (:content resp)
            msg        {:role "assistant" :content content}
            stop-reason (:stop_reason resp)
            tool-use (->>(filter #(= "tool_use" (:type %)) content)(first))]
        (swap! messages conj msg)
        (mu/log ::usage :data (get resp :usage))
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
                         :max_tokens  1024
                         :messages    @messages
                         :temperature 0
                         :stream      false} 
                        (@context :system) (assoc :system (@context :system))
                        tools (assoc :tools tools)
                        tool-choice (assoc :tool_choice {:type "tool" :name tool-choice}))
                       (request-anthropic-messages))]
        (if (:isError response)
          (do 
            (mu/log ::error :data response)
            {:isError   true
             :message (get response :message)}
          )
          response))))
  (add-tool-result [this tool-use] (add-tool-result this tool-use nil))
  (add-tool-result [this tool-use clients]
                   (let [result (tool-time tool-use)
                         msg    {:role    "user"
                                 :content [{:type        "tool_result"
                                            :tool_use_id (tool-use :id)
                                            :content     (result :result)}]}]
                     (parse-response this (prompt-ai this msg))))
  
  MessageOps
  (get-last-text [this]
                 (let [msg (peek @messages)]
                   (assoc msg :content (get-in msg [:content 0 :text]))))
  (save-messages [this] (save-state! @messages (str "accent-anthropic-messages-" (System/currentTimeMillis) ".json")))
  (save-messages [this file] (save-state! @messages file))
  (reset-messages [this] (reset! messages []))
  
  AgentOps
  (get-model [this] model)
  (switch-model [this new-model] (set! model new-model))
  (show-tools [this] tools)
  (get-context [this] @context))

;;==========================================
;; Models
;;==========================================

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
  {:default "claude-3-7-sonnet-latest"
   :models {"claude-3-7-sonnet-latest" {:label "Claude 3.7 Sonnet"
                                        :context 200000}
            "claude-3-sonnet-20240229" {:label "Claude 3 Sonnet"
                                        :context 200000}}})

;; =============================================================================
;; Agent Configuration w/ Tools
;; =============================================================================

(defn create-agent
  "Create an agent, possibly endowed with tools, using config and optional add'l context"
  [agent-config & {:keys [context]}]
  (let [provider (:provider agent-config)
        model (:model agent-config)
        role (:role agent-config)
        tool-set (:tools agent-config)]
    (case provider
      :openai (OpenAIProvider. model
                               (atom [{:role "system" :content role}])
                               (select-tools tool-set :openai)
                               (create-tool-dispatcher tool-set)
                               (if context context (atom {})))
      :anthropic (AnthropicProvider. model
                                     (atom [])
                                     (select-tools tool-set :anthropic)
                                     (create-anthropic-tool-dispatcher tool-set)
                                     (if context context (atom {:system role}))))))

;; =============================================================================
;; Basic Chat Access to Agent
;; =============================================================================

(defn ask [agent prompt]
  (->> prompt
       (prompt-ai agent)
       (parse-response agent)))

(defn chat [agent]
  (println "Chat initialized. Your message:")
  (loop [prompt (read-line)]
    (let [ai-reply (ask agent prompt)]
      (println "assistant:" (ai-reply :content))
      (when-not (:final ai-reply)
        (print "user: ")
        (flush)
        (recur (read-line))))))
