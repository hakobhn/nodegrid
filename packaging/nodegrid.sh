#!/usr/bin/env sh
# NodeGrid launcher (Linux).  Usage: ./nodegrid.sh [master|worker] [extra Spring Boot args, e.g. --server.port=9090]
set -e

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
cd "$APP_HOME"

ROLE="${1:-master}"
case "$ROLE" in
  master|worker) ;;
  *) echo "Usage: $0 [master|worker] [extra Spring Boot args]"; exit 1 ;;
esac
[ $# -gt 0 ] && shift

# Prefer the bundled runtime, then JAVA_HOME, then java on PATH.
if [ -f "$APP_HOME/jre/bin/java" ]; then
  # Archives extracted by some tools lose the executable bit – restore it.
  if [ ! -x "$APP_HOME/jre/bin/java" ]; then
    chmod +x "$APP_HOME"/jre/bin/* "$APP_HOME"/jre/lib/jspawnhelper 2>/dev/null || true
  fi
  JAVA="$APP_HOME/jre/bin/java"
elif [ -n "$JAVA_HOME" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi

echo "Starting NodeGrid as $ROLE (logs: $APP_HOME/logs)"
# shellcheck disable=SC2086
exec "$JAVA" $JAVA_OPTS -jar "$APP_HOME/nodegrid.jar" --spring.profiles.active="$ROLE" "$@"
