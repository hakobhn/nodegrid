#!/usr/bin/env bash
# Builds self-contained NodeGrid bundles (app jar + Java runtime + launch scripts):
#   dist/nodegrid-windows-x64/  + dist/nodegrid-windows-x64.zip     (.bat launchers)
#   dist/nodegrid-linux-x64/    + dist/nodegrid-linux-x64.tar.gz    (.sh launchers)
#
# Usage:   ./build-dist.sh [target ...]        targets: windows-x64 linux-x64 linux-aarch64
# Env:     JAVA_VERSION (default 21)  SKIP_BUILD=1 (reuse target/nodegrid.jar)
# Needs:   JDK (mvn + jar), curl, tar.  On Windows run it through Git Bash or build-dist.cmd.
#
# The Java runtime is the Eclipse Temurin JRE, downloaded once from api.adoptium.net and cached in .jre-cache/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAVA_VERSION="${JAVA_VERSION:-21}"
TARGETS="${*:-windows-x64 linux-x64}"
DIST="$ROOT/dist"
CACHE="$ROOT/.jre-cache"
cd "$ROOT"

if [ -n "${JAVA_HOME:-}" ]; then
  PATH="$JAVA_HOME/bin:$PATH"
fi
command -v jar >/dev/null || { echo "'jar' not found – install a JDK or set JAVA_HOME"; exit 1; }

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "==> Building jar"
  mvn -B -q clean package -DskipTests
fi
JAR="$ROOT/target/nodegrid.jar"
[ -f "$JAR" ] || { echo "Missing $JAR"; exit 1; }

mkdir -p "$DIST" "$CACHE"

for target in $TARGETS; do
  os="${target%%-*}"
  arch="${target#*-}"
  case "$os" in
    windows) ext=zip ;;
    linux)   ext=tar.gz ;;
    *) echo "Unsupported target '$target' (use windows-x64, linux-x64, linux-aarch64)"; exit 1 ;;
  esac

  archive="$CACHE/jre-$JAVA_VERSION-$target.$ext"
  if [ ! -s "$archive" ]; then
    url="https://api.adoptium.net/v3/binary/latest/$JAVA_VERSION/ga/$os/$arch/jre/hotspot/normal/eclipse"
    echo "==> Downloading Temurin JRE $JAVA_VERSION for $target"
    curl -fL --retry 3 -o "$archive.part" "$url"
    mv "$archive.part" "$archive"
  fi

  name="nodegrid-$target"
  out="$DIST/$name"
  tmp="$CACHE/extract-$target"
  echo "==> Assembling $name"
  rm -rf "$out" "$tmp" "$DIST/$name.zip" "$DIST/$name.tar.gz"
  mkdir -p "$out" "$tmp"

  if [ "$ext" = zip ]; then
    (cd "$tmp" && jar xf "$archive")
  else
    tar -xzf "$archive" -C "$tmp"
  fi
  jre_dir="$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  mv "$jre_dir" "$out/jre"
  rm -rf "$tmp"

  cp "$JAR" "$out/"
  cp -r "$ROOT/packaging/config" "$out/"
  cp "$ROOT/packaging/README.txt" "$out/"

  if [ "$os" = windows ]; then
    for f in "$ROOT"/packaging/*.bat; do
      # cmd.exe wants CRLF line endings
      sed 's/\r$//; s/$/\r/' "$f" > "$out/$(basename "$f")"
    done
    (cd "$DIST" && jar -cfM "$name.zip" "$name")
    echo "    -> dist/$name.zip"
  else
    for f in "$ROOT"/packaging/*.sh; do
      sed 's/\r$//' "$f" > "$out/$(basename "$f")"
    done
    chmod +x "$out"/*.sh "$out"/jre/bin/* 2>/dev/null || true
    if tar --version 2>/dev/null | grep -q 'GNU tar'; then
      # Set Unix permissions explicitly so the archive is correct even when built on Windows.
      (cd "$DIST" \
        && tar -cf "$name.tar" --owner=0 --group=0 --mode='u=rwX,go=rX' \
             --exclude="$name/jre/bin" --exclude="$name/jre/lib/jspawnhelper" --exclude="$name/*.sh" "$name" \
        && extra="" && { [ -f "$name/jre/lib/jspawnhelper" ] && extra="$name/jre/lib/jspawnhelper" || true; } \
        && tar -rf "$name.tar" --owner=0 --group=0 --mode=755 "$name/jre/bin" "$name"/*.sh $extra \
        && gzip -f "$name.tar")
    else
      (cd "$DIST" && tar -czf "$name.tar.gz" "$name")
    fi
    echo "    -> dist/$name.tar.gz"
  fi
done

echo "==> Done. Bundles are in $DIST"
