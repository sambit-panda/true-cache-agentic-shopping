#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}"
OJDBC="$MAVEN_REPO_LOCAL/com/oracle/database/jdbc/ojdbc11/23.5.0.24.07/ojdbc11-23.5.0.24.07.jar"
UCP="$MAVEN_REPO_LOCAL/com/oracle/database/jdbc/ucp11/23.5.0.24.07/ucp11-23.5.0.24.07.jar"
if [[ ! -f "$OJDBC" || ! -f "$UCP" ]]; then
  echo "Run mvn -Poracle -DskipTests package first so ojdbc11 and ucp11 are available." >&2
  exit 1
fi

: "${ORA_USER:?Set the Oracle demo username}"
: "${ORA_PASSWORD:?Set the Oracle demo password}"
: "${ORA_PRIMARY_URL:?Set the Oracle primary JDBC URL}"
: "${ORA_CACHE_URL:?Set the Oracle True Cache JDBC URL}"
export LLM_RESPONSES_URL="${LLM_RESPONSES_URL:-http://127.0.0.1:8080/v1/responses}"
export LLM_MODEL="${LLM_MODEL:-qwen3-4b}"

CLASSPATH="target/classes:$OJDBC:$UCP"
if [[ "${1:-}" == "--smoke-test" ]]; then
  exec java -cp "$CLASSPATH" agentorders.OracleSmokeTest
fi
exec java -cp "$CLASSPATH" agentorders.LocalModelOracleDemo "$@"
