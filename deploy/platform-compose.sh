#!/usr/bin/env bash
# 用中央端口注册表调用任一统一门户项目的 Docker Compose。
# 用法: ./deploy/platform-compose.sh <auth|langchain4j|recsys|drools|risk|workflow|recon|benefit|marketing> <compose 参数...>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=load-platform-ports.sh
. "${SCRIPT_DIR}/load-platform-ports.sh"

PROJECT="${1:-}"
[[ -n "${PROJECT}" ]] || { echo "用法: $0 <项目> <compose 参数...>" >&2; exit 2; }
shift
[[ $# -gt 0 ]] || { echo "缺少 Docker Compose 参数" >&2; exit 2; }

LOCAL_ENV=''
case "${PROJECT}" in
  auth)
    PROJECT_DIR="${WORKSPACE_DIR}/auth-platform/deploy"
    COMPOSE_FILE="${PROJECT_DIR}/docker-compose.yml"
    [[ -f "${PROJECT_DIR}/.env" ]] && LOCAL_ENV="${PROJECT_DIR}/.env"
    ;;
  langchain4j)
    PROJECT_DIR="${WORKSPACE_DIR}/langchain4j-platform/deploy"
    COMPOSE_FILE="${PROJECT_DIR}/docker-compose.yml"
    [[ -f "${PROJECT_DIR}/.env" ]] && LOCAL_ENV="${PROJECT_DIR}/.env"
    ;;
  recsys)
    PROJECT_DIR="${WORKSPACE_DIR}/recsys/docker"
    COMPOSE_FILE="${PROJECT_DIR}/docker-compose.yml"
    [[ -f "${WORKSPACE_DIR}/recsys/scripts/dev-local.env" ]] \
      && LOCAL_ENV="${WORKSPACE_DIR}/recsys/scripts/dev-local.env"
    ;;
  drools)
    PROJECT_DIR="${WORKSPACE_DIR}/drools-demo/deploy"
    COMPOSE_FILE="${PROJECT_DIR}/docker-compose.yml"
    ;;
  risk)
    PROJECT_DIR="${WORKSPACE_DIR}/risk-platform"
    COMPOSE_FILE="${PROJECT_DIR}/docker-compose.yml"
    [[ -f "${PROJECT_DIR}/.env" ]] && LOCAL_ENV="${PROJECT_DIR}/.env"
    ;;
  workflow)
    PROJECT_DIR="${WORKSPACE_DIR}/workflow-platform/deploy"
    COMPOSE_FILE="${PROJECT_DIR}/docker-compose.yml"
    [[ -f "${PROJECT_DIR}/.env" ]] && LOCAL_ENV="${PROJECT_DIR}/.env"
    ;;
  recon)
    PROJECT_DIR="${WORKSPACE_DIR}/recon-platform"
    COMPOSE_FILE="${PROJECT_DIR}/compose.yml"
    ;;
  benefit)
    PROJECT_DIR="${WORKSPACE_DIR}/benefit-center/deploy"
    COMPOSE_FILE="${PROJECT_DIR}/docker-compose.yml"
    [[ -f "${PROJECT_DIR}/.env" ]] && LOCAL_ENV="${PROJECT_DIR}/.env"
    ;;
  marketing)
    PROJECT_DIR="${WORKSPACE_DIR}/marketing-lowcode-platform/deploy"
    COMPOSE_FILE="${PROJECT_DIR}/compose.yaml"
    [[ -f "${WORKSPACE_DIR}/marketing-lowcode-platform/.env" ]] \
      && LOCAL_ENV="${WORKSPACE_DIR}/marketing-lowcode-platform/.env"
    if [[ -z "${DEV_INFRA_REDIS_URL:-}" && -f "${WORKSPACE_DIR}/dev-infra/.env" ]]; then
      DEV_INFRA_REDIS_URL="redis://:$(
        set -a
        # shellcheck disable=SC1091
        source "${WORKSPACE_DIR}/dev-infra/.env"
        printf '%s' "${REDIS7_PASSWORD:-}"
      )@infra-redis7:6379"
      export DEV_INFRA_REDIS_URL
    fi
    ;;
  *) echo "未知项目: ${PROJECT}" >&2; exit 2 ;;
esac

COMPOSE=(docker compose)
[[ -n "${LOCAL_ENV}" ]] && COMPOSE+=(--env-file "${LOCAL_ENV}")
COMPOSE+=(--env-file "${PLATFORM_PORTS_FILE}" -f "${COMPOSE_FILE}")

if [[ "${PROJECT}" == auth ]]; then
  for ARG in "$@"; do
    if [[ "${ARG}" == up ]]; then
      node "${SCRIPT_DIR}/sync-platform-catalog.mjs"
      break
    fi
  done
fi

cd "${PROJECT_DIR}"
exec "${COMPOSE[@]}" "$@"
