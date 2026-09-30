#!/usr/bin/env bash
# 唯一 Compose 入口；只操作本任务的治理服务，不隐式重建 OA/旧 Auth 中间件。
set -Eeuo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
env_file=${GOVERNANCE_ENV_FILE:-$repo/.local/docker-governance/runtime.env}
[[ -f "$env_file" ]] || { echo "Missing private runtime.env: $env_file" >&2; exit 1; }
command -v docker >/dev/null
compose=(docker compose --env-file "$env_file" -p auth-platform -f "$repo/deploy/docker-compose.yml" --profile governance)
services=(auth-console governance-admin governance-projector)
case "${1:-status}" in
  build)
    cd "$repo"
    ./mvnw -q -pl auth-platform-admin -am package -DskipTests
    "${compose[@]}" build auth-console governance-admin
    ;;
  up)
    "${compose[@]}" config --quiet
    "${compose[@]}" up -d --no-build --pull never --wait --wait-timeout 180 auth-console governance-admin
    python3 "$repo/deploy/governance/provision-local.py" --runtime-env "$env_file" --activate
    "${compose[@]}" up -d --no-build --pull never --wait --wait-timeout 180 "${services[@]}"
    ;;
  restart)
    # 共享网络命名空间不能只重建 UI；三者必须一起更新。
    "${compose[@]}" up -d --force-recreate --no-build --pull never --wait --wait-timeout 180 "${services[@]}"
    ;;
  stop) "${compose[@]}" stop "${services[@]}" ;;
  status) "${compose[@]}" ps "${services[@]}" ;;
  *) echo "Usage: $0 {build|up|restart|stop|status}" >&2; exit 2 ;;
esac
