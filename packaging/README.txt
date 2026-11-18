NodeGrid
========

Self-contained bundle: includes its own Java runtime (jre/), no Java installation needed.

Start
-----
  Windows:  double-click start-master.bat   or   start-worker.bat
  Linux:    ./start-master.sh                or   ./start-worker.sh
            (if you get "permission denied":  sh start-master.sh)

  Generic:  nodegrid.bat|nodegrid.sh <master|worker> [--any.spring.property=value ...]
            e.g.  ./nodegrid.sh worker --server.port=8082 --nodegrid.master-url=http://10.0.0.5:8080

Web UI
------
  Master:  http://<host>:8080   - cluster overview, worker list, settings
  Worker:  http://<host>:8081   - local resources, master connection, settings

The master's Overview page shows its URL. Paste it on a worker's Overview page, or set
nodegrid.master-url. The worker connects to the master, reports its resources and fetches
commands from it. The master never connects to workers, so only the master's port (8080)
has to be reachable from the workers.

Files
-----
  config/application-master.yml   master configuration (port, intervals ...)
  config/application-worker.yml   worker configuration (port, master URL ...)
  data/                           settings edited in the UI + known workers (created on first run)
  logs/                           log files
  JAVA_OPTS environment variable  extra JVM options, e.g. JAVA_OPTS=-Xmx512m

Running a master and a worker on the same machine works out of the box (ports 8080 / 8081).
For several workers on one machine give each its own port: --server.port=8082
