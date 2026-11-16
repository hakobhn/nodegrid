#!/usr/bin/env sh
# Starts this machine as a NodeGrid worker (UI: http://localhost:8081).
# Set the master address in config/application-worker.yml (nodegrid.master-url) or pass --nodegrid.master-url=http://host:8080
exec sh "$(dirname "$0")/nodegrid.sh" worker "$@"
