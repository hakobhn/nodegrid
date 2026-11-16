# NodeGrid

One **master** node and many **worker** nodes, all from a single jar. Every node runs a web server with a
management UI. The master keeps the list of workers and polls each worker's resources periodically.

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
             ┌────────────── master :8080 ──────────────┐
             │  WorkerRegistry ◄── POST /register ──────┼──── worker heartbeat (every 15s)
             │  WorkerPoller   ─── GET /resources ──────┼───► each worker   (every 10s, parallel)
             │  Web UI: overview · workers · settings   │
             └──────────────────────────────────────────┘
```

* **Joining:** a worker registers itself with `nodegrid.master-url` on startup and re-registers on every
  heartbeat, so it reappears automatically after a master restart. You can also add a worker by URL in the master UI.
* **Monitoring:** the master polls `GET /api/node/resources` on every worker in parallel, using virtual threads.
  Each failure counts against the worker: it is marked `UNREACHABLE`, and after `offline-after-failures`
  consecutive failures it becomes `OFFLINE`.
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
| master | POST | `/api/master/workers` | add worker manually `{ "url": "http://host:8081" }` |
| master | POST | `/api/master/workers/register` | worker self-registration / heartbeat |
| master | POST | `/api/master/workers/{id}/poll` | poll one worker now |
| master | DELETE | `/api/master/workers/{id}` | remove worker |
| master | GET | `/api/master/summary` | cluster totals |

## Project layout

```
src/main/java/com/nodegrid
├── config/     NodeProperties (nodegrid.*), NodeRole
├── node/       resource collection + /api/node endpoints (every node)
├── settings/   runtime-editable settings, persisted to data/
├── client/     node-to-node HTTP client
├── master/     worker registry, poller, /api/master endpoints   (role=master only)
├── worker/     heartbeat/registration to master                  (role=worker only)
└── web/        error handling
src/main/resources/static/   web UI (plain HTML/JS/CSS)
packaging/                   launch scripts + config shipped in the bundles
build-dist.sh / .cmd         bundle builder
```

## Roadmap

- [ ] Task model: submit a job on the master, split it into parallel chunks, assign them to workers based on free resources
- [ ] Worker task executor + progress reporting back to the master
- [ ] Authentication between nodes (shared token) and for the UI
- [ ] Resource history / charts
