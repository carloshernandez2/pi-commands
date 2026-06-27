# pi-commands

CLI toolkit for [pi](https://github.com/earendil-works/pi-coding-agent): web search, page extraction, and multi-agent orchestration.

[![GitHub](https://img.shields.io/badge/github-carloshernandez2/pi-commands-blue)](https://github.com/carloshernandez2/pi-commands)

## Quick Reference

```bash
pi-commands web-search "query" [-n NUM] [-c CATEGORY] [-j]
pi-commands fetch-page "https://example.com"
pi-commands agent <subcommand> [args...]
pi-commands help
```

---

## Web Search

Backed by a local SearXNG instance (`localhost:8888`).

```bash
# Basic search (10 results)
pi-commands web-search "rust async runtimes 2025"

# Limit results
pi-commands web-search "query" -n 5

# Filter by category
pi-commands web-search "query" -c IT
# Categories: general, images, news, videos, IT, science, files, music

# Raw JSON output
pi-commands web-search "query" -j
```

---

## Page Extraction

Downloads a URL and extracts readable text using `trafilatura`.

```bash
pi-commands fetch-page "https://example.com/article"
```

---

## Multi-Agent Orchestration

Spawns independent pi AgentSession instances managed by a background service. Each agent has its own conversation, tools, and LLM calls. All commands return fast (non-blocking).

The service auto-starts on the first agent command.

### Commands

```bash
# Spawn a new agent
pi-commands agent spawn <name> "prompt" [--model provider/model-id]

# Send a follow-up message to an existing agent
pi-commands agent message <name> "prompt"

# View an agent's conversation
pi-commands agent messages <name> [--tail N]

# List all running agents
pi-commands agent status

# Delete an agent
pi-commands agent delete <name>
```

### Examples

```bash
# Spawn two agents in parallel
pi-commands agent spawn architect "Design a REST API for task management"
pi-commands agent spawn reviewer "Review this design for security issues"

# Check status
pi-commands agent status
# === Agents: 2 running ===
# architect   [streaming]   1 msgs
# reviewer    [idle]        0 msgs

# Send a follow-up
pi-commands agent message architect "Also add rate limiting"

# Read the last 3 messages from an agent
pi-commands agent messages architect --tail 3

# Use a specific model
pi-commands agent spawn --model "anthropic/claude-sonnet-4-20250514" coder "Implement the API"

# Clean up
pi-commands agent delete architect
```

### Architecture

```
pi-commands (thin wrapper in ~/.bin/)
  └─ ~/.pi/pi-commands/pi-commands (main script)
       └─ systemd → pi-agents-daemon.service
            └─ ~/.pi/pi-commands/daemon/index.ts (Node.js)
                 └─ Unix socket → ~/.pi/agent/.daemon/sock
                      └─ AgentSession "architect" (SDK, in-process)
                      └─ AgentSession "reviewer"  (SDK, in-process)
                      └─ ...
```

Agents run in-process via the pi SDK (not subprocesses), giving direct access to session state, messages, and streaming events.

### Layout

| Path | Purpose |
|------|---------|
| `~/.bin/pi-commands` | Thin wrapper (on PATH) |
| `~/.pi/pi-commands/` | Source code, docs |
| `~/.pi/pi-commands/daemon/` | Daemon source (Node.js) |
| `~/.pi/agent/.daemon/` | Runtime data (socket, PID) |
| `~/.pi/agent/agent-sessions/` | Agent working directories |
| `~/.pi/agent/sessions/` | Conversation data (JSONL) |
| `~/.config/systemd/user/pi-agents-daemon.service` | systemd service |

### Logs

- `journalctl --user -u pi-agents-daemon.service`
