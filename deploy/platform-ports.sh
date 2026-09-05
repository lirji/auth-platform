#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-platform-ports.sh
. "${SCRIPT_DIR}/load-platform-ports.sh"

sync_catalog(){ node "${SCRIPT_DIR}/sync-platform-catalog.mjs" "$@"; }

show_ports(){
  printf '%-18s %s\n' 'auth portal' "${AUTH_PORTAL_UI_PORT}"
  printf '%-18s %s\n' 'langchain4j' "${LANGCHAIN4J_UI_PORT}"
  printf '%-18s %s\n' 'recsys' "${RECSYS_UI_PORT}"
  printf '%-18s %s\n' 'drools' "${DROOLS_UI_PORT}"
  printf '%-18s %s\n' 'risk' "${RISK_UI_PORT}"
  printf '%-18s %s\n' 'workflow' "${WORKFLOW_UI_PORT}"
  printf '%-18s %s\n' 'reconciliation' "${RECON_UI_PORT}"
  printf '%-18s %s\n' 'benefit' "${BENEFIT_UI_PORT}"
  printf '%-18s %s\n' 'marketing' "${MARKETING_UI_PORT}"
}

verify_compose(){
  local project="$1" service="$2" target="$3" expected="$4"
  "${SCRIPT_DIR}/platform-compose.sh" "${project}" --profile '*' config --format json \
    | node "${SCRIPT_DIR}/verify-compose-port.mjs" "${service}" "${target}" "${expected}"
}

check_all(){
  sync_catalog --check
  verify_compose auth project-portal 80 "${AUTH_PORTAL_UI_PORT}"
  verify_compose langchain4j capability-showcase-frontend 80 "${LANGCHAIN4J_UI_PORT}"
  verify_compose recsys console 80 "${RECSYS_UI_PORT}"
  verify_compose drools gateway 80 "${DROOLS_UI_PORT}"
  verify_compose risk risk-console 8080 "${RISK_UI_PORT}"
  verify_compose workflow console 8302 "${WORKFLOW_UI_PORT}"
  verify_compose recon console 8088 "${RECON_UI_PORT}"
  verify_compose benefit console 8083 "${BENEFIT_UI_PORT}"
  verify_compose marketing console 8080 "${MARKETING_UI_PORT}"
  echo '中央端口注册表、门户 catalog 与 9 个 Compose 映射一致'
}

case "${1:-show}" in
  show) show_ports ;;
  sync) sync_catalog; check_all ;;
  check) check_all ;;
  *) echo "用法: $0 [show|sync|check]" >&2; exit 2 ;;
esac
