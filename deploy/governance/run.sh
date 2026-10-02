#!/usr/bin/env bash
# 治理 Compose 入口；只操作治理服务，不隐式重建 OA/旧 Auth 中间件。
set -Eeuo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
env_file=${GOVERNANCE_ENV_FILE:-$repo/.local/docker-governance/runtime.env}
command -v docker >/dev/null
action=${1:-status}
case "$action" in
  build|config|up|update|restart|stop|status) ;;
  *) echo "Usage: $0 {build|config|up|update|restart|stop|status}" >&2; exit 2 ;;
esac
compose=(docker compose)
if [[ -f "$env_file" ]]; then
  compose+=(--env-file "$env_file")
elif [[ "$action" != build && "$action" != config ]]; then
  echo "Missing private runtime.env: $env_file" >&2
  exit 1
fi
# 浏览器入口只能来自中央注册表；私密 env 不得另设一份端口。
# shellcheck source=../load-platform-ports.sh
. "$repo/deploy/load-platform-ports.sh"
compose+=(--env-file "$PLATFORM_PORTS_FILE" -p auth-platform -f "$repo/deploy/docker-compose.yml" --profile governance)
services=(auth-console governance-admin governance-projector)
build_services(){
  "${compose[@]}" config --quiet
  "${compose[@]}" build auth-console governance-admin
}
start_services(){
  "${compose[@]}" config --quiet
  "${compose[@]}" up -d --no-build --pull never --wait --wait-timeout 180 auth-console governance-admin
  python3 "$repo/deploy/governance/provision-local.py" --runtime-env "$env_file" --activate
  "${compose[@]}" up -d --no-build --pull never --wait --wait-timeout 180 "${services[@]}"
}
case "$action" in
  build) build_services ;;
  config) "${compose[@]}" config --quiet ;;
  up) start_services ;;
  update)
    # 成功构建全部镜像后才更新运行容器，避免构建失败造成半更新。
    build_services
    start_services
    ;;
  restart)
    # 共享网络命名空间不能只重建 UI；三者必须一起更新。
    "${compose[@]}" up -d --force-recreate --no-build --pull never --wait --wait-timeout 180 "${services[@]}"
    ;;
  stop) "${compose[@]}" stop "${services[@]}" ;;
  status) "${compose[@]}" ps "${services[@]}" ;;
esac
