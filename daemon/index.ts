#!/usr/bin/env -S npx tsx
/**
 * pi-agents-daemon
 *
 * Long-running process that manages multiple pi AgentSession instances.
 * Communicates over a Unix socket using JSONL (one JSON object per line).
 *
 * Commands (sent on stdin, one JSON per line):
 *   { cmd: "spawn", name: string, prompt: string, model?: string }
 *   { cmd: "message", name: string, prompt: string }
 *   { cmd: "messages", name: string, tail?: number }
 *   { cmd: "status" }
 *   { cmd: "delete", name: string }         // remove agent registration
 *
 * Responses (written to stdout, one JSON per line, matched by `id`):
 *   { id: string, ok: boolean, data?: any, error?: string }
 */

import net from "node:net";
import fs from "node:fs";
import path from "node:path";

import {
  createAgentSession,
  SessionManager,
  AuthStorage,
  ModelRegistry,
  getAgentDir,
  type AgentSession,
  type AgentSessionEvent,
} from "@earendil-works/pi-coding-agent";

// ── Paths ──────────────────────────────────────────────────────────
const AGENT_DIR = getAgentDir();
const DAEMON_DIR = path.join(AGENT_DIR, ".daemon");
const SOCK_PATH = path.join(DAEMON_DIR, "sock");
const PID_PATH = path.join(DAEMON_DIR, "pid");
const SESSIONS_DIR = path.join(AGENT_DIR, "agent-sessions");

// ── Logging (stdout → journalctl via systemd) ──────────────────────
function log(...args: unknown[]): void {
  const ts = new Date().toISOString();
  const line = `[${ts}] ${args.map(a => typeof a === "object" ? JSON.stringify(a) : String(a)).join(" ")}`;
  console.log(line);
}

// ── State ──────────────────────────────────────────────────────────
const agents = new Map<string, AgentState>();
let requestCounter = 0;

interface AgentState {
  session: AgentSession;
  prompt: string;
  createdAt: number;
  lastActivity: number;
  // buffer assistant text deltas for streaming preview
  currentText: string;
}

// ── Helpers ────────────────────────────────────────────────────────

function respond(socket: net.Socket, id: string, data: unknown): void {
  socket.write(JSON.stringify({ id, ok: true, data }) + "\n");
}

function error(socket: net.Socket, id: string, message: string): void {
  socket.write(JSON.stringify({ id, ok: false, error: message }) + "\n");
}

function ensureDir(dir: string): void {
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
}

function agentSessionDir(name: string): string {
  return path.join(SESSIONS_DIR, name);
}

// ── Agent lifecycle ────────────────────────────────────────────────

async function createAgent(
  name: string,
  prompt: string,
  modelPattern?: string,
): Promise<AgentState> {
  const dir = agentSessionDir(name);
  ensureDir(dir);

  const authStorage = AuthStorage.create();
  const modelRegistry = ModelRegistry.create(authStorage);

  const { session } = await createAgentSession({
    cwd: process.cwd(),
    sessionManager: SessionManager.create(dir),
    authStorage,
    modelRegistry,
    settingsManager: undefined, // use defaults
  });

  if (modelPattern) {
    const found = modelRegistry.find(
      modelPattern.split("/")[0],
      modelPattern.split("/").slice(1).join("/"),
    );
    if (found) await session.setModel(found);
  }

  const state: AgentState = {
    session,
    prompt,
    createdAt: Date.now(),
    lastActivity: Date.now(),
    currentText: "",
  };

  // Subscribe to events to track streaming state and buffer text
  session.subscribe((event: AgentSessionEvent) => {
    state.lastActivity = Date.now();
    if (
      event.type === "message_update" &&
      event.assistantMessageEvent.type === "text_delta"
    ) {
      state.currentText += event.assistantMessageEvent.delta;
    }
    if (event.type === "agent_end") {
      state.currentText = "";
    }
  });

  agents.set(name, state);
  log("Agent created:", name);

  // Fire off the initial prompt (non-blocking)
  session.prompt(prompt).catch((err) => {
    log("Prompt error for", name, ":", err);
  });

  return state;
}



function deleteAgent(name: string): void {
  const state = agents.get(name);
  if (state) {
    const dir = agentSessionDir(name);
    log("Deleting agent:", name);
    state.session.dispose();
    agents.delete(name);
    // Remove the entire session directory
    try { fs.rmSync(dir, { recursive: true, force: true }); } catch {}
  }
}

// ── Command handlers ───────────────────────────────────────────────

async function handleSpawn(
  socket: net.Socket,
  id: string,
  name: string,
  prompt: string,
  model?: string,
): Promise<void> {
  if (agents.has(name)) {
    return error(socket, id, `Agent "${name}" already exists`);
  }
  try {
    await createAgent(name, prompt, model);
    return respond(socket, id, { name, status: "spawned" });
  } catch (err) {
    return error(socket, id, `Failed to spawn: ${err}`);
  }
}

async function handleMessage(
  socket: net.Socket,
  id: string,
  name: string,
  prompt: string,
): Promise<void> {
  const state = agents.get(name);
  if (!state) return error(socket, id, `Agent "${name}" not found`);
  try {
    state.session.prompt(prompt).catch(() => {});
    return respond(socket, id, { name, status: "message queued" });
  } catch (err) {
    return error(socket, id, `Failed to message: ${err}`);
  }
}

function handleMessages(
  socket: net.Socket,
  id: string,
  name: string,
  tail?: number,
): void {
  const state = agents.get(name);
  if (!state) return error(socket, id, `Agent "${name}" not found`);

  const messages = state.session.messages;
  const sliced = tail ? messages.slice(-tail) : messages;

  // Format messages for display
  const formatted = sliced.map((msg, i) => {
    const role = msg.role;
    let content = "";
    if (typeof msg.content === "string") {
      content = msg.content;
    } else if (Array.isArray(msg.content)) {
      content = msg.content
        .filter((b) => b.type === "text")
        .map((b) => (b as { text: string }).text)
        .join("\n");
    }
    return { index: i, role, content };
  });

  respond(socket, id, {
    name,
    messageCount: messages.length,
    shown: formatted.length,
    messages: formatted,
  });
}

function handleStatus(socket: net.Socket, id: string): void {
  const list = Array.from(agents.entries()).map(([name, state]) => ({
    name,
    streaming: state.session.isStreaming,
    messageCount: state.session.messages.length,
    preview:
      state.currentText.length > 120
        ? state.currentText.slice(0, 120) + "…"
        : state.currentText || null,
    createdAt: new Date(state.createdAt).toISOString(),
    lastActivity: new Date(state.lastActivity).toISOString(),
  }));

  respond(socket, id, { agents: list });
}

function handleDelete(socket: net.Socket, id: string, name: string): void {
  const state = agents.get(name);
  if (!state) return error(socket, id, `Agent "${name}" not found`);
  deleteAgent(name);
  respond(socket, id, { name, status: "deleted" });
}

// ── Socket server ──────────────────────────────────────────────────

function cleanup(): void {
  log("Cleanup: disposing", agents.size, "agent(s)");
  for (const name of agents.keys()) {
    const state = agents.get(name);
    if (state) { state.session.dispose(); agents.delete(name); }
  }
  try { fs.unlinkSync(SOCK_PATH); } catch {}
  try { fs.unlinkSync(PID_PATH); } catch {}
  log("Cleanup complete");
}

function startServer(): net.Server {
  // Clean up stale socket
  try { fs.unlinkSync(SOCK_PATH); } catch {}

  const server = net.createServer((socket) => {
    let buffer = "";

    socket.on("data", (chunk: Buffer) => {
      buffer += chunk.toString();
      const lines = buffer.split("\n");
      buffer = lines.pop() || "";

      for (const line of lines) {
        if (!line.trim()) continue;
        try {
          const cmd = JSON.parse(line);
          // Fire-and-forget with error catching (can't await in sync event handler)
          processCommand(socket, cmd).catch((err) => {
            log("command error:", err);
          });
        } catch {
          // ignore malformed lines
        }
      }
    });

    socket.on("close", () => {
      // Daemon stays alive regardless of socket connections.
    });
  });

  server.on("error", (err) => {
    log("Daemon socket error:", err);
    process.exit(1);
  });

  server.listen(SOCK_PATH, () => {
    // Write PID file
    fs.writeFileSync(PID_PATH, process.pid.toString());
    // Make socket world-readable for the user
    try { fs.chmodSync(SOCK_PATH, 0o600); } catch {}
    log("Daemon listening on", SOCK_PATH, "(pid", process.pid, ")");
  });

  return server;
}

async function processCommand(socket: net.Socket, raw: unknown): Promise<void> {
  if (typeof raw !== "object" || raw === null) return;
  const cmd = raw as Record<string, unknown>;
  const id = `r${++requestCounter}`;

  switch (cmd.cmd) {
    case "spawn": {
      const name = String(cmd.name ?? "");
      const prompt = String(cmd.prompt ?? "");
      if (!name || !prompt) return error(socket, id, "spawn requires name and prompt");
      await handleSpawn(socket, id, name, prompt, cmd.model ? String(cmd.model) : undefined);
      break;
    }
    case "message": {
      const name = String(cmd.name ?? "");
      const prompt = String(cmd.prompt ?? "");
      if (!name || !prompt) return error(socket, id, "message requires name and prompt");
      await handleMessage(socket, id, name, prompt);
      break;
    }
    case "messages": {
      const name = String(cmd.name ?? "");
      if (!name) return error(socket, id, "messages requires name");
      handleMessages(socket, id, name, cmd.tail ? Number(cmd.tail) : undefined);
      break;
    }
    case "status":
      handleStatus(socket, id);
      break;
    case "delete": {
      const name = String(cmd.name ?? "");
      if (!name) return error(socket, id, "delete requires name");
      handleDelete(socket, id, name);
      break;
    }
    default:
      error(socket, id, `Unknown command: ${cmd.cmd}`);
  }
}

// ── Startup ────────────────────────────────────────────────────────

let server: net.Server;

async function main(): Promise<void> {
  log("Daemon starting (pid", process.pid, ")");
  ensureDir(DAEMON_DIR);
  ensureDir(SESSIONS_DIR);

  server = startServer();
}

main().catch((err) => {
  console.error("Daemon failed:", err);
  cleanup();
  process.exit(1);
});

// Graceful shutdown
process.on("SIGINT", () => { log("SIGINT received"); cleanup(); process.exit(0); });
process.on("SIGTERM", () => { log("SIGTERM received"); cleanup(); process.exit(0); });
