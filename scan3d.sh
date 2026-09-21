#!/usr/bin/env bash
# Compila (se necessário) e executa o scan3d. Uso: ./scan3d.sh scan --approx-camera   |   ./scan3d.sh --help
set -euo pipefail
cd "$(dirname "$0")"

# Homebrew instala o JDK 21 "keg-only"; usa-o se existir e JAVA_HOME não estiver definido.
if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@21 ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21
fi
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

JAR=target/scan3d.jar
if [ ! -f "$JAR" ] || [ -n "$(find src pom.xml -newer "$JAR" -print -quit)" ]; then
  echo "Compilando..." >&2
  mvn -q -B -DskipTests package
fi
exec "$JAVA" -jar "$JAR" "$@"
