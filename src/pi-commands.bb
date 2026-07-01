#!/usr/bin/env bb
;; pi-commands — Dispatcher for web search, page extraction, and multi-agent orchestration
;; Usage: pi-commands <command> [args...]

;; ── Dependencies ──────────────────────────────────────────────────
(require '[babashka.cli :as cli]
         '[babashka.http-client :as http]
         '[babashka.process :refer [shell process]]
         '[cheshire.core :as json]
         '[clojure.string :as str])

;; ── Configuration ─────────────────────────────────────────────────
(def SEARXNG-URL
  (or (System/getenv "SEARXNG_URL") "http://localhost:8888"))

(def USER-AGENT
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")

(def DAEMON-SOCK
  (str (System/getenv "HOME") "/.pi/agent/.daemon/sock"))

;; ── Helpers ───────────────────────────────────────────────────────
(defn err-println
  "Print to stderr."
  [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn exit-error
  "Print error(s) to stderr and exit 1."
  [& msgs]
  (doseq [m msgs]
    (err-println m))
  (System/exit 1))

;; ── Daemon helpers ────────────────────────────────────────────────
(defn daemon-running?
  "Check if the agent daemon systemd service is active."
  []
  (zero? (:exit (shell {:out :string :err :string}
                            "systemctl --user is-active --quiet pi-agents-daemon.service"))))

(defn daemon-start
  "Ensure the agent daemon is running. Exits on failure."
  []
  (when-not (daemon-running?)
    (let [result (shell {:out :string :err :string}
                             "systemctl --user start pi-agents-daemon.service")]
      (when (not= (:exit result) 0)
        (exit-error "agent: failed to start agent service"))
      (let [found? (loop [i 0]
                     (if (or (>= i 30)
                             (-> (java.io.File. DAEMON-SOCK) .exists))
                       (< i 30)
                       (do (Thread/sleep 500)
                           (recur (inc i)))))]
        (when-not found?
          (exit-error "agent: service failed to start"))))))

(defn- read-socket-response
  "Read from socket until we get a complete line or timeout.
  Uses non-blocking mode because setSoTimeout is not supported on
  Unix domain sockets in Java. Returns accumulated string.
  Defined as top-level defn to avoid SCI forward-reference restrictions."
  [ch buf sb start-time timeout-ms]
  (loop []
    (let [elapsed (- (System/currentTimeMillis) start-time)]
      (if (>= elapsed timeout-ms)
        (.toString sb)
        (do
          (.clear buf)
          (let [n (.read ch buf)]
            (if (> n 0)
              (do
                (.flip buf)
                (.append sb (String. (.array buf) 0 n "UTF-8"))
                (let [accum (.toString sb)]
                  (if (.contains accum "\n")
                    accum
                    (recur))))
              (do (Thread/sleep 50) (recur)))))))))

(defn agent-send
  "Send a JSON command to the daemon via Unix domain socket using Java NIO.
   Returns the parsed daemon response map on success,
   or {:error msg} on connection failure."
  [cmd-map]
  (try
    (let [addr (java.net.UnixDomainSocketAddress/of DAEMON-SOCK)
          ch (java.nio.channels.SocketChannel/open addr)
          _ (.configureBlocking ch false)
          _ (.write ch (java.nio.ByteBuffer/wrap (.getBytes (str (json/generate-string cmd-map) "\n"))))
          buf (java.nio.ByteBuffer/allocate 65536)
          sb (StringBuilder.)
          response-str (str/trim (read-socket-response ch buf sb (System/currentTimeMillis) 5000))]
      (.close ch)
      (if (str/blank? response-str)
        {:error "empty response"}
        (json/parse-string response-str keyword)))
    (catch Exception e
      {:error (str "service not responding: " (.getMessage e))})))

(defn- agent-op
  "Ensure daemon is running, send cmd-map, and call success-fn with inner data.
   Exits on connection failure or daemon-reported error."
  [cmd-map success-fn]
  (daemon-start)
  (let [result (agent-send cmd-map)]
    (if (:error result)
      (exit-error (str "agent: " (:error result)))
      (if (:ok result)
        (success-fn (:data result))
        (exit-error (str "agent: " (or (:error result) "unknown error")))))))

(defn- get-prompt
  "Join :prompt opt with remaining positional args into a single string."
  [ctx]
  (str/join " " (filter some? (cons (:prompt (:opts ctx)) (:args ctx)))))

;; ── Command handlers ──────────────────────────────────────────────
(defn handle-web-search
  "Query SearXNG and return results."
  [ctx]
  (let [opts (:opts ctx)
        query (:query opts)
        num-results (:n opts)
        category (:c opts)
        raw-json (:j opts)]
    (when (str/blank? query)
      (exit-error "web-search: no query"))
    (try
      (let [response (http/get (str SEARXNG-URL "/search")
                               {:query-params {:q query
                                               :categories category
                                               :format "json"}})
            body (json/parse-string (:body response) keyword)
            results (or (:results body) [])]
        (if raw-json
          (println (json/generate-string (take num-results results)))
          (do
            (let [total (count results)]
              (println (str "=== Search: \"" query "\" ("
                            total " results found, showing " num-results ") ==="))
              (println))
            (doseq [[i result] (map-indexed vector (take num-results results))]
              (println (str "## " (inc i) ". " (:title result)))
              (println (str "URL: " (:url result)))
              (println (str "Source: " (or (:engine result) "unknown")))
              (println (or (:content result) "No snippet available."))
              (println)))))
      (catch Exception _
        (exit-error (str "web-search: failed to reach SearXNG at " SEARXNG-URL))))))

(defn handle-fetch-page
  "Download a URL and extract readable text via trafilatura."
  [ctx]
  (let [url (:url (:opts ctx))]
    (when (str/blank? url)
      (exit-error "fetch-page: no URL"))
    (try
      (let [response (http/get url
                               {:headers {"User-Agent" USER-AGENT}})
            p (process "trafilatura" {:out :string :err :string
                                           :in (:body response)})
            result @p]
        (when-let [out (:out result)]
          (print out)))
      (catch Exception _
        ;; Silently fail, matching bash `|| true` behavior
        nil))))

(defn handle-agent-spawn
  "Spawn a new agent."
  [ctx]
  (let [opts (:opts ctx)
        name (:name opts)
        prompt (get-prompt ctx)
        model (:model opts)
        cwd (System/getProperty "user.dir")]
    (when (or (str/blank? name) (str/blank? prompt))
      (exit-error "agent spawn: requires name and prompt"
                  "Usage: pi-commands agent spawn <name> [\"prompt\"]"))
    (agent-op (cond-> {:cmd "spawn" :name name :prompt prompt :cwd cwd}
               (not (str/blank? model)) (assoc :model model))
              (fn [_] (println (str "Spawned agent: " name))))))

(defn handle-agent-message
  "Send a message to an existing agent."
  [ctx]
  (let [opts (:opts ctx)
        name (:name opts)
        prompt (get-prompt ctx)]
    (when (or (str/blank? name) (str/blank? prompt))
      (exit-error "agent message: requires name and prompt"))
    (agent-op {:cmd "message" :name name :prompt prompt}
              (fn [_] (println (str "Message sent to: " name))))))

(defn handle-agent-messages
  "Retrieve messages from an agent."
  [ctx]
  (let [opts (:opts ctx)
        name (:name opts)
        tail (:tail opts)]
    (when (str/blank? name)
      (exit-error "agent messages: requires name"))
    (agent-op (cond-> {:cmd "messages" :name name}
               tail (assoc :tail tail))
              (fn [data]
                (let [count (:messageCount data)
                      shown (:shown data)]
                  (println (str "=== Agent: " name " (" count " messages, showing " shown ") ==="))
                  (println)
                  (doseq [msg (:messages data)]
                    (println (str "[" (:role msg) "] " (:content msg)))))))))

(defn- format-tokens
  "Format token counts with K/M suffixes."
  [^long n]
  (cond
    (>= n 1000000) (str (format "%.1fM" (double (/ n 1000000))))
    (>= n 1000)   (str (format "%.1fK" (double (/ n 1000))))
    :else         (str n)))

(defn handle-agent-status
  "Show status of all running agents."
  [_ctx]
  (agent-op {:cmd "status"}
             (fn [data]
               (let [agents (:agents data)]
                 (println (str "=== Agents: " (count agents) " running ==="))
                 (println)
                 (doseq [agent agents]
                   (let [tokens (:tokens agent)
                         cost (:cost agent)
                         token-str (when tokens
                                     (str " Tokens: " (format-tokens (or (:total tokens) 0))))
                         cost-str (when cost
                                    (str " Cost: $" (format "%.4f" (double cost))))]
                     (println (str (:name agent) "\t["
                                   (if (:streaming agent) "streaming" "idle")
                                   "]\t" (:messageCount agent) " msgs"
                                   token-str
                                   cost-str
                                   (when (:model agent)
                                     (str "\tModel: " (:model agent)))
                                   (when (:preview agent)
                                     (str "\t" (:preview agent)))))))))))

(defn handle-agent-delete
  "Delete an agent."
  [ctx]
  (let [name (:name (:opts ctx))]
    (when (str/blank? name)
      (exit-error "agent delete: requires name"))
    (agent-op {:cmd "delete" :name name}
              (fn [_] (println (str "Deleted agent: " name))))))

(def HELP-TEXT
  "pi-commands — Dispatcher for web search, page extraction, and multi-agent orchestration
Usage: pi-commands <command> [args...]

Commands:
  web-search  \"query\" [-n NUM] [-c CATEGORY] [-j]
      Query SearXNG and return results.
      -n NUM   : number of results (default 10)
      -c CAT   : category (general, images, news, videos, IT, science, files, music)
      -j       : raw JSON output

  fetch-page  \"url\"
      Download a URL and extract readable text.

  agent       <subcommand> [args...]
      Multi-agent orchestration (spawn/manage pi sub-agents).
      Subcommands: spawn, message, messages, status, delete.

  help        Show this message.")

(defn handle-unknown
  "Catch-all for unknown commands and help."
  [ctx]
  (let [opts (:opts ctx)
        args (:args ctx)
        first-arg (first args)]
    (cond
      (or (:help opts) (:h opts)) (println HELP-TEXT)
      (nil? first-arg) (exit-error "pi-commands: no command given"
                                   "Usage: pi-commands <web-search|fetch-page|agent|help> [args...]")
      :else (exit-error (str "pi-commands: unknown command '" first-arg "'")
                        "Usage: pi-commands <web-search|fetch-page|agent|help> [args...]"))))

;; ── Dispatch table ────────────────────────────────────────────────
(def ^:private dispatch-table
  [{:cmds ["web-search"]
    :fn handle-web-search
    :spec {:n   {:alias :n :coerce :long :default 10
                 :desc "Number of results"}
           :c   {:alias :c :default "general"
                 :desc "Category (general, images, news, videos, IT, science, files, music)"}
           :j   {:alias :j :desc "Raw JSON output"}}
    :args->opts [:query]}

   {:cmds ["fetch-page"]
    :fn handle-fetch-page
    :args->opts [:url]}

   {:cmds ["agent" "spawn"]
    :fn handle-agent-spawn
    :spec {:model {:desc "Model to use"}}
    :args->opts [:name :prompt]}

   {:cmds ["agent" "message"]
    :fn handle-agent-message
    :args->opts [:name :prompt]}

   {:cmds ["agent" "messages"]
    :fn handle-agent-messages
    :spec {:tail {:alias :t :coerce :long :desc "Number of recent messages"}}
    :args->opts [:name]}

   {:cmds ["agent" "status"]
    :fn handle-agent-status}

   {:cmds ["agent" "delete"]
    :fn handle-agent-delete
    :args->opts [:name]}

   {:cmds [] :fn handle-unknown}])

;; ── Main ──────────────────────────────────────────────────────────
(defn -main
  "Entry point: parse args and dispatch via babashka.cli."
  []
  (try
    (cli/dispatch dispatch-table *command-line-args* {:help true})
    (catch clojure.lang.ExceptionInfo e
      (when (= :input-exhausted (:cause (ex-data e)))
        (let [dispatch-path (:dispatch (ex-data e))]
          (if (= ["agent"] dispatch-path)
            (exit-error "agent: no subcommand"
                        "Usage: pi-commands agent <spawn|message|messages|status|delete>")
            (exit-error "pi-commands: incomplete command"
                        "Usage: pi-commands <web-search|fetch-page|agent|help> [args...]"))))
      (throw e))))

(-main)
