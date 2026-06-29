#!/usr/bin/env bb
;; Install pi-commands: deploy CLI + daemon, set up wrapper and systemd service.
(require '[babashka.process :refer [shell]])

(def home (System/getenv "HOME"))
(def src-dir (.getParentFile (.getParentFile (java.io.File. *file*)))) ;; repo root
(def install (str home "/.pi/pi-commands-install"))
(def wrapper (str home "/.bin/pi-commands"))
(def service-src (str (str src-dir "/systemd/pi-agents-daemon.service")))
(def service-dest (str home "/.config/systemd/user/pi-agents-daemon.service"))

(println "Installing pi-commands...")

;; Deploy CLI and daemon — cp -n skips if already present
(shell {:dir install} "mkdir" "-p" "." "daemon")
(shell "cp" "-n" (str src-dir "/src/pi-commands.bb") install)
(shell "cp" "-n" (str src-dir "/daemon/index.ts") (str install "/daemon"))
(shell "cp" "-n" (str src-dir "/daemon/package.json") (str install "/daemon"))

;; Install daemon dependencies if node_modules is missing
(when-not (.exists (java.io.File. (str install "/daemon/node_modules")))
  (println "Installing daemon dependencies...")
  (shell {:dir (str install "/daemon")} "npm" "install"))

;; Install wrapper on PATH — write to temp, cmp, then move if changed
(let [content (format "#!/usr/bin/env bash\nexec bb \"%s/pi-commands.bb\" \"$@\"\n" install)
      tmp (str wrapper ".tmp")]
  (spit tmp content)
  (if (zero? (:exit (shell {:continue true} "cmp" "-s" tmp wrapper)))
    (do (println "Wrapper up to date")
        (shell "rm" tmp))
    (do (shell "mv" tmp wrapper)
        (shell "chmod" "+x" wrapper))))

;; Install systemd service file (first time only; warn if changed)
(if (.exists (java.io.File. service-dest))
  (when-not (zero? (:exit (shell {:continue true} "cmp" "-s" service-src service-dest)))
    (println "\u2139 systemd service file changed — update manually:")
    (println (format "  cp %s %s" service-src service-dest))
    (println "  systemctl --user daemon-reload && systemctl --user restart pi-agents-daemon.service"))
  (do
    (shell {:dir (str home "/.config/systemd/user")} "mkdir" "-p" ".")
    (shell "cp" service-src service-dest)
    (shell {:continue true} "systemctl" "--user" "daemon-reload")))

(println "Done — try: pi-commands --help")
