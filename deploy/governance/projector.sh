#!/usr/bin/env bash
# 显式允许列表，每个 CLI 批次自身限制 30 秒；错误退出由 Docker 有界退避重启。
set -Eeuo pipefail
shopt -s nullglob
configs=(/run/governance/projections/*.properties)
(( ${#configs[@]} > 0 && ${#configs[@]} <= 8 )) || { echo "PROJECTION_CONFIG_COUNT_INVALID" >&2; exit 1; }
while true; do
  ready=true
  for config in "${configs[@]}"; do
    result=0
    java -Xmx192m -Dloader.main=com.lrj.authz.admin.governance.ReliableProjectionCli \
      -cp /app/admin.jar org.springframework.boot.loader.launch.PropertiesLauncher "$config" || result=$?
    case "$result" in
      0) ;;
      2) ready=false ;;
      *) echo "PROJECTION_BATCH_FAILED" >&2; exit "$result" ;;
    esac
  done
  if "$ready"; then date +%s > /tmp/projection-ready; fi
  sleep 10 & wait $!
done
