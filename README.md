# NodeGrid

One **master** node and many **worker** nodes, all from a single jar. Every node runs a web server with a
management UI. The master shows its URL; each worker is given that URL and connects to it. Workers report their
resources to the master, and the master sends requests (commands) back over a connection the worker opened.
Only the master has to be reachable on the network.

Current scope: **resource monitoring** (CPU, memory, swap, JVM heap, disks, OS info).
Next step: splitting tasks into parallel chunks and distributing them across workers (see [Roadmap](#roadmap)).

## Quick start (development)

Requirements: JDK 21+ and Maven 3.9+.

```bash
mvn clean package
java -jar target/nodegrid.jar --spring.profiles.active=master   # UI on http://localhost:8080
java -jar target/nodegrid.jar --spring.profiles.active=worker   # UI on http://localhost:8081
```

More workers on the same machine: `--spring.profiles.active=worker --server.port=8082`.

## Distribution (bundled Java, ready to run)

```bash
./build-dist.sh            # or build-dist.cmd on Windows
```

This produces self-contained bundles in `dist/`. Each one has the jar, its own Temurin JRE 21 and launch scripts:

| Bundle | Launchers |
|---|---|
| `nodegrid-windows-x64.zip` | `start-master.bat`, `start-worker.bat`, `nodegrid.bat` |
| `nodegrid-linux-x64.tar.gz` | `start-master.sh`, `start-worker.sh`, `nodegrid.sh` |

Pass `linux-aarch64` to the script for ARM Linux. Users only unpack the bundle and run a start script. They
don't need to install Java. See [packaging/README.txt](packaging/README.txt) for what ends up in the bundle.

## How it works

```
             ┌────────────── master :8080 ───────────────┐
             │  WorkerRegistry ◄── POST /register ───────┼──── heartbeat + resources (every 15s)
             │  CommandQueue   ◄── GET  /commands/next ──┼──── long-poll held open (≤25s), commands returned on it
             │                 ◄── POST /…/result ───────┼──── command result
             │  Web UI: overview · workers · settings    │          every arrow is opened by the worker
             └───────────────────────────────────────────┘
```

* **Joining:** the master's Overview page shows its URL. Paste it on the worker's Overview page, or set
  `nodegrid.master-url`. The worker registers on startup and on every heartbeat, so it reappears automatically
  after a master restart.
* **Monitoring:** every heartbeat carries the worker's resource snapshot. If a worker misses a heartbeat it is
  marked `UNREACHABLE`. After `offline-after-failures` missed heartbeats it becomes `OFFLINE`.
* **Commands (master → worker):** the master queues a command for a worker. The worker always keeps a long-poll
  open on `/api/master/workers/{id}/commands/next`, so the command goes out right away on that connection.
  The worker runs it with the matching `CommandHandler` bean and posts the result back. The master never
  connects to a worker, so workers behind NAT or a firewall still work. Built-in commands: `ping` (echoes the
  payload) and `resources`. To add a new kind of work, register another `CommandHandler` bean on the worker. It
  is advertised to the master automatically.
* **Settings:** the UI can change most settings at runtime. Changes are saved to
  `data/<role>-<port>/node.json` and override `application.yml` until you choose *Reset to defaults*.
  The role (master/worker) and the port are fixed at startup.

## REST API

| Node | Method | Path | Purpose |
|---|---|---|---|
| any | GET | `/api/node/info` | id, role, name, advertised URL, master link (workers) |
| any | GET | `/api/node/resources` | resource snapshot |
| any | GET / PUT / DELETE | `/api/node/settings` | read / update / reset runtime settings |
| master | GET | `/api/master/workers` | all workers with latest resources |
| master | GET | `/api/master/workers/{id}` | one worker |
| master | POST | `/api/master/workers/register` | worker heartbeat (registration + resources + command types) |
| master | DELETE | `/api/master/workers/{id}` | remove worker |
| master | POST | `/api/master/workers/{id}/commands` | send a command `{ "type": "ping", "payload": {…}, "timeoutSeconds": 300 }` |
| master | GET | `/api/master/workers/{id}/commands` | recent commands of a worker with status and result |
| master | GET | `/api/master/commands/{commandId}` | one command |
| master | GET | `/api/master/workers/{id}/commands/next?wait=25` | worker long-poll for commands |
| master | POST | `/api/master/workers/{id}/commands/{commandId}/result` | worker reports a result |
| master | GET | `/api/master/summary` | cluster totals |

## Project layout

```
src/main/java/com/nodegrid
├── config/     NodeProperties (nodegrid.*), NodeRole
├── node/       resource collection + /api/node endpoints (every node)
├── settings/   runtime-editable settings, persisted to data/
├── client/     worker → master HTTP client
├── command/    command DTOs + CommandHandler interface
├── master/     worker registry, command queue, /api/master endpoints   (role=master only)
├── worker/     heartbeat, command long-poll runner, built-in handlers  (role=worker only)
└── web/        error handling
src/main/resources/static/   web UI (plain HTML/JS/CSS)
packaging/                   launch scripts + config shipped in the bundles
build-dist.sh / .cmd         bundle builder
```

## Roadmap

- [ ] Task model: submit a job on the master, split it into parallel chunks, assign them to workers based on free resources
- [x] Master → worker command channel (worker-initiated long-poll)
- [ ] Worker task executor + progress reporting back to the master
- [ ] Authentication between nodes (shared token) and for the UI
- [ ] Resource history / charts
