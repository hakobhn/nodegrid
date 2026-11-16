#!/usr/bin/env sh
# Starts this machine as the NodeGrid master (UI: http://localhost:8080)
exec sh "$(dirname "$0")/nodegrid.sh" master "$@"
