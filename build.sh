#!/usr/bin/env bash
# Dependency-free build and run (JDK 21).
#   ./build.sh [test|demo|agent|all] [agent args...]
# If redis-server is installed and nothing listens on REDIS_ADDR (default 127.0.0.1:6390), a throwaway
# Redis is started for the run and stopped afterwards, so the Redis tests and demos use a real server.
set -euo pipefail
cd "$(dirname "$0")"
export REDIS_ADDR="${REDIS_ADDR:-127.0.0.1:6390}"
HOST="${REDIS_ADDR%%:*}"; PORT="${REDIS_ADDR##*:}"

REDIS_PID=""
if command -v redis-server >/dev/null && ! (exec 3<>"/dev/tcp/$HOST/$PORT") 2>/dev/null; then
  redis-server --port "$PORT" --bind "$HOST" --save "" --appendonly no >/dev/null 2>&1 &
  REDIS_PID=$!
  trap '[ -n "$REDIS_PID" ] && { kill "$REDIS_PID" 2>/dev/null; wait "$REDIS_PID" 2>/dev/null; } || true' EXIT
  for _ in $(seq 1 50); do (exec 3<>"/dev/tcp/$HOST/$PORT") 2>/dev/null && break; sleep 0.1; done
fi

rm -rf out && mkdir out
javac -Xlint:all -d out $(find src/main/java -name '*.java')
MODE="${1:-all}"; shift || true
case "$MODE" in
  test)  java -cp out agentorders.Tests ;;
  demo)  java -cp out agentorders.Demo ;;
  agent) java -cp out agentorders.AgentDemo "$@" ;;
  all)   java -cp out agentorders.Tests && java -cp out agentorders.Demo && java -cp out agentorders.AgentDemo "$@" ;;
esac
