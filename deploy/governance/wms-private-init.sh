#!/usr/bin/env bash
set -Eeuo pipefail
# Docker Desktop的宿主0600文件不能直接交给非root应用读取；仅受控初始化复制，保持每服务独立卷。
for service in inventory inbound outbound fulfillment serial-registry; do
  install -d -o 10001 -g 10001 -m 0700 "/private/$service"
  install -o 10001 -g 10001 -m 0600 "/bootstrap/$service.properties" "/private/$service/central.properties"
  install -o 10001 -g 10001 -m 0600 /bootstrap/truststore.p12 "/private/$service/truststore.p12"
done
# 原对账文件已过期且主体不匹配；只复制本任务明确发行的机器身份，不覆盖原宿主文件。
install -d -o 10001 -g 10001 -m 0700 /private/inventory/recon-tokens
if [[ -f /bootstrap/recon-worker.jwt ]]; then
  enterprise_file=$(printf '%s' ENT-DEMO | sha256sum | cut -d' ' -f1)
  install -o 10001 -g 10001 -m 0600 /bootstrap/recon-worker.jwt "/private/inventory/recon-tokens/$enterprise_file.jwt"
fi
install -d -o 10001 -g 10001 -m 0700 /private/server
for file in server.properties application.properties server.p12 ca.pem; do
  install -o 10001 -g 10001 -m 0600 "/bootstrap/$file" "/private/server/$file"
done
