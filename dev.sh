#!/usr/bin/env bash
# =============================================================================
# dev.sh — 统一权限平台 本地全栈「一键启停」脚本
# -----------------------------------------------------------------------------
# 一条命令拉起完整开发环境, 依赖顺序:
#   基建(docker compose: postgres + spicedb + casdoor)
#     └─ 后端 server(:8200) / admin(:8201)   [Spring Boot]
#          └─ 前端 auth-console(:5273)         [Vite dev]
#
# 用法:
#   ./dev.sh                 = ./dev.sh up   (一键启动全部)
#   ./dev.sh up   [flags]    启动 (幂等: 已在跑的层会跳过)
#   ./dev.sh down [flags]    停止全部 (含基建)
#   ./dev.sh restart [flags] 先 down 再 up
#   ./dev.sh status          查看各服务健康
#   ./dev.sh logs [name]     跟踪日志 (name = server|admin|frontend, 缺省全部)
#
# flags (对 up/down/restart 生效):
#   --no-infra       跳过 docker 基建 (基建已在别处跑时用)
#   --no-backend     跳过后端 server/admin
#   --no-frontend    跳过前端 auth-console
#   --skip-build     up 时跳过 mvn install (确定已构建过时提速)
#   -f, --follow     up 完成后前台跟踪日志, Ctrl-C 一并停掉全部
#
# 依赖: docker(compose v2), java21, mvnw, pnpm(或 npm), curl。注释一律中文。
# =============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$ROOT/logs"
PID_DIR="$LOG_DIR/pids"
FRONTEND_DIR="$ROOT/auth-console"
DEPLOY_DIR="$ROOT/deploy"

# ---- 端口 (与 application.yml / docker-compose / vite.config 保持一致) ----
SERVER_PORT="${SERVER_PORT:-8200}"
ADMIN_PORT="${ADMIN_PORT:-8201}"
FRONTEND_PORT="${FRONTEND_PORT:-5273}"
SPICEDB_HTTP_PORT="${SPICEDB_HTTP_PORT:-8543}"
CASDOOR_PORT="${CASDOOR_PORT:-8000}"

# ---- 开关 (被 flag 覆盖) ----
DO_INFRA=1; DO_BACKEND=1; DO_FRONTEND=1; SKIP_BUILD=0; FOLLOW=0

# ---- 彩色输出 ----
if [[ -t 1 ]]; then
  C_G=$'\033[32m'; C_Y=$'\033[33m'; C_R=$'\033[31m'; C_B=$'\033[36m'; C_D=$'\033[2m'; C_0=$'\033[0m'
else
  C_G=; C_Y=; C_R=; C_B=; C_D=; C_0=
fi
info(){ printf '%s\n' "${C_B}==>${C_0} $*"; }
ok(){   printf '%s\n' "${C_G} ✔${C_0} $*"; }
warn(){ printf '%s\n' "${C_Y} ▲${C_0} $*"; }
err(){  printf '%s\n' "${C_R} ✘${C_0} $*" >&2; }
die(){  err "$*"; exit 1; }

# ---- 工具依赖检查 ----
need(){ command -v "$1" >/dev/null 2>&1 || die "缺少依赖: $1"; }

# 前端包管理器: 优先 pnpm, 回退 npm
PM=""
pick_pm(){
  if command -v pnpm >/dev/null 2>&1; then PM="pnpm";
  elif command -v npm >/dev/null 2>&1; then PM="npm";
  else die "前端需要 pnpm 或 npm"; fi
}

# ---- 端口/进程工具 ----
# 某 TCP 端口是否被监听 (macOS/Linux 通用, 走 lsof)
port_in_use(){ lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
# 杀掉监听某端口的所有进程 (兜底清理, 防 maven 派生子 JVM 残留)
kill_port(){
  local pids; pids="$(lsof -nP -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null)"
  [[ -n "$pids" ]] && kill $pids 2>/dev/null || true
}

# 轮询 HTTP 端点直到可应答 (拿到任意 http_code 即认为服务已起)
wait_http(){ # url label timeout_sec
  local url="$1" label="$2" timeout="${3:-120}" i=0 code
  printf '%s' "    等待 ${label} "
  while (( i < timeout )); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url" 2>/dev/null || echo 000)"
    if [[ "$code" != 000 ]]; then printf ' %s(HTTP %s)%s\n' "$C_D" "$code" "$C_0"; return 0; fi
    printf '.'; sleep 1; i=$((i+1))
  done
  printf '\n'; return 1
}

# 后台启动一个长驻进程, 记录 PID + 日志 (幂等: 端口已占则跳过)
# 用法: spawn <name> <port> <workdir> <cmd...>
spawn(){
  local name="$1" port="$2" wd="$3"; shift 3
  if port_in_use "$port"; then warn "${name} 端口 :${port} 已被占用 — 视为已在运行, 跳过"; return 0; fi
  local log="$LOG_DIR/${name}.log" pidf="$PID_DIR/${name}.pid"
  info "启动 ${name} (:${port}) ${C_D}→ ${log}${C_0}"
  ( cd "$wd" && exec "$@" ) >"$log" 2>&1 &
  echo $! >"$pidf"
}

# 停止 spawn 出来的进程 (PID + 其子进程), 再按端口兜底清理
stop_named(){ # name port
  local name="$1" port="$2"
  local pidf="$PID_DIR/${name}.pid" pid=""
  [[ -f "$pidf" ]] && pid="$(cat "$pidf" 2>/dev/null)"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    info "停止 ${name} (pid ${pid})"
    pkill -TERM -P "$pid" 2>/dev/null || true   # 先杀子进程 (maven 派生的 JVM)
    kill -TERM "$pid" 2>/dev/null || true
  fi
  kill_port "$port"                              # 兜底: 清掉仍占端口的残留
  rm -f "$pidf"
}

# ============================ 各层启动 ======================================

start_infra(){
  need docker
  info "基建: docker compose up -d (postgres + spicedb + casdoor)"
  ( cd "$DEPLOY_DIR" && docker compose up -d ) || die "docker compose 启动失败"
  wait_http "http://localhost:${SPICEDB_HTTP_PORT}/healthz" "SpiceDB(:${SPICEDB_HTTP_PORT})" 90 \
    && ok "SpiceDB 就绪" || warn "SpiceDB 健康检查超时 (查 docker logs authz-spicedb)"
  wait_http "http://localhost:${CASDOOR_PORT}/" "Casdoor(:${CASDOOR_PORT})" 120 \
    && ok "Casdoor 就绪" || warn "Casdoor 健康检查超时 (首次启动较慢, 查 docker logs authz-casdoor)"
}

build_backend(){
  [[ "$SKIP_BUILD" == 1 ]] && { warn "跳过 mvn 构建 (--skip-build)"; return 0; }
  need java
  info "构建后端: ./mvnw -q -DskipTests install (首次较慢)"
  ( cd "$ROOT" && ./mvnw -q -DskipTests install ) || die "Maven 构建失败, 详见上方输出"
  ok "后端构建完成"
}

start_backend(){
  build_backend
  # install 后依赖已进本地仓库, spring-boot:run 无需 -am, 单模块编译即可
  spawn server "$SERVER_PORT" "$ROOT" ./mvnw -q -pl auth-platform-server spring-boot:run
  spawn admin  "$ADMIN_PORT"  "$ROOT" ./mvnw -q -pl auth-platform-admin  spring-boot:run
  wait_http "http://localhost:${SERVER_PORT}/actuator/health" "server(:${SERVER_PORT})" 150 \
    && ok "server 就绪" || warn "server 未就绪, 查 ${LOG_DIR}/server.log"
  wait_http "http://localhost:${ADMIN_PORT}/actuator/health" "admin(:${ADMIN_PORT})" 150 \
    && ok "admin 就绪" || warn "admin 未就绪, 查 ${LOG_DIR}/admin.log"
}

start_frontend(){
  pick_pm
  if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
    info "前端依赖缺失, 执行 ${PM} install"
    ( cd "$FRONTEND_DIR" && "$PM" install ) || die "前端依赖安装失败"
  fi
  spawn frontend "$FRONTEND_PORT" "$FRONTEND_DIR" "$PM" run dev
  wait_http "http://localhost:${FRONTEND_PORT}/" "auth-console(:${FRONTEND_PORT})" 90 \
    && ok "前端就绪" || warn "前端未就绪, 查 ${LOG_DIR}/frontend.log"
}

# ============================ 子命令 ======================================

cmd_up(){
  need curl
  mkdir -p "$PID_DIR"
  [[ "$DO_INFRA"    == 1 ]] && start_infra
  [[ "$DO_BACKEND"  == 1 ]] && start_backend
  [[ "$DO_FRONTEND" == 1 ]] && start_frontend
  print_summary
  if [[ "$FOLLOW" == 1 ]]; then
    info "跟踪日志中 — Ctrl-C 将停止全部服务"
    trap 'echo; cmd_down; exit 0' INT TERM
    tail -n +1 -F "$LOG_DIR"/server.log "$LOG_DIR"/admin.log "$LOG_DIR"/frontend.log 2>/dev/null
  fi
}

cmd_down(){
  info "停止应用进程 (前端 / admin / server)"
  [[ "$DO_FRONTEND" == 1 ]] && stop_named frontend "$FRONTEND_PORT"
  [[ "$DO_BACKEND"  == 1 ]] && { stop_named admin "$ADMIN_PORT"; stop_named server "$SERVER_PORT"; }
  if [[ "$DO_INFRA" == 1 ]]; then
    info "停止基建: docker compose down --remove-orphans"
    ( cd "$DEPLOY_DIR" && docker compose down --remove-orphans ) || warn "docker compose down 失败"
  fi
  ok "已停止"
}

cmd_status(){
  printf '%s\n' "${C_B}服务状态${C_0}"
  _stat(){ # label url
    local code; code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$2" 2>/dev/null || echo 000)"
    if [[ "$code" == 000 ]]; then printf '  %-30s %s✘ down%s\n' "$1" "$C_R" "$C_0"
    else printf '  %-30s %s● up%s %s(%s)%s\n' "$1" "$C_G" "$C_0" "$C_D" "$code" "$C_0"; fi
  }
  _stat "前端 auth-console :$FRONTEND_PORT" "http://localhost:${FRONTEND_PORT}/"
  _stat "后端 server :$SERVER_PORT"         "http://localhost:${SERVER_PORT}/actuator/health"
  _stat "后端 admin :$ADMIN_PORT"           "http://localhost:${ADMIN_PORT}/actuator/health"
  _stat "SpiceDB :$SPICEDB_HTTP_PORT"       "http://localhost:${SPICEDB_HTTP_PORT}/healthz"
  _stat "Casdoor :$CASDOOR_PORT"            "http://localhost:${CASDOOR_PORT}/"
}

cmd_logs(){
  local name="${1:-}"
  case "$name" in
    server|admin|frontend) tail -n 100 -F "$LOG_DIR/${name}.log" ;;
    "") tail -n 40 -F "$LOG_DIR"/server.log "$LOG_DIR"/admin.log "$LOG_DIR"/frontend.log 2>/dev/null ;;
    *) die "未知日志: $name (可选 server|admin|frontend)" ;;
  esac
}

print_summary(){
  cat <<EOF

${C_G}━━━━━━━━━━━━━━━━━━━━━ 环境就绪 ━━━━━━━━━━━━━━━━━━━━━${C_0}
  前端 auth-console   ${C_B}http://localhost:${FRONTEND_PORT}${C_0}
  后端 server         ${C_B}http://localhost:${SERVER_PORT}${C_0}   ${C_D}(/actuator/health)${C_0}
  后端 admin          ${C_B}http://localhost:${ADMIN_PORT}${C_0}   ${C_D}(/actuator/health)${C_0}
  Casdoor 登录        ${C_B}http://localhost:${CASDOOR_PORT}${C_0}
  SpiceDB HTTP        ${C_B}http://localhost:${SPICEDB_HTTP_PORT}${C_0}

  日志   ${C_D}./dev.sh logs [server|admin|frontend]${C_0}
  状态   ${C_D}./dev.sh status${C_0}
  停止   ${C_D}./dev.sh down${C_0}
${C_G}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${C_0}
EOF
}

usage(){ sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; }

# ============================ 参数解析 ====================================
CMD="up"
case "${1:-}" in up|down|restart|status|logs|help|-h|--help) CMD="$1"; shift ;; esac

# logs 的第一个位置参数是服务名, 单独处理
if [[ "$CMD" == "logs" ]]; then cmd_logs "${1:-}"; exit $?; fi
if [[ "$CMD" == "help" || "$CMD" == "-h" || "$CMD" == "--help" ]]; then usage; exit 0; fi

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-infra)    DO_INFRA=0 ;;
    --no-backend)  DO_BACKEND=0 ;;
    --no-frontend) DO_FRONTEND=0 ;;
    --skip-build)  SKIP_BUILD=1 ;;
    -f|--follow)   FOLLOW=1 ;;
    *) die "未知参数: $1 (见 ./dev.sh help)" ;;
  esac
  shift
done

case "$CMD" in
  up)      cmd_up ;;
  down)    cmd_down ;;
  restart) cmd_down; echo; cmd_up ;;
  status)  cmd_status ;;
esac
