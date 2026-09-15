#!/usr/bin/env bash
# 幂等开通授权管控台独立 Casdoor 应用 auth-console，并保证 built-in/admin 属于 authz-admin。
# 用法：
#   bash deploy/auth-console-provision.sh
# 可选：CASDOOR_URL、CASDOOR_ADMIN、CASDOOR_ADMIN_PW、AUTH_CONSOLE_CLIENT_ID、
#       AUTH_CONSOLE_CLIENT_SECRET、REDIRECT_URIS、AUTHZ_POSTGRES_CONTAINER。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AUTH_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=load-platform-ports.sh
. "${SCRIPT_DIR}/load-platform-ports.sh"
# shellcheck source=casdoor-builtin-app.sh
. "${SCRIPT_DIR}/casdoor-builtin-app.sh"

ORG="built-in"
APP="auth-console"
CLIENT_ID="${AUTH_CONSOLE_CLIENT_ID:-auth-console}"
CLIENT_SECRET="${AUTH_CONSOLE_CLIENT_SECRET:-auth-console-local-secret-20260915}"
USER_NAME="${CASDOOR_ADMIN:-admin}"
CASDOOR="${CASDOOR_URL:-http://localhost:8000}"
ADMIN="${CASDOOR_ADMIN:-admin}"
ADMIN_PW="${CASDOOR_ADMIN_PW:-123}"
POSTGRES_CONTAINER="${AUTHZ_POSTGRES_CONTAINER:-authz-postgres}"
UI_PORT="${AUTH_CONSOLE_UI_PORT:-5273}"
REDIRECT_URIS="${REDIRECT_URIS:-http://localhost:${UI_PORT}/callback,http://127.0.0.1:${UI_PORT}/callback,http://localhost:8202/callback,http://127.0.0.1:8202/callback}"
ENV_FILE="${AUTH_ROOT}/auth-console/.env.local"

for command_name in curl jq docker; do
  command -v "${command_name}" >/dev/null || { echo "缺少命令：${command_name}" >&2; exit 1; }
done

casdoor_load_builtin_app "${POSTGRES_CONTAINER}"
casdoor_ensure_builtin_password_grant

ADMIN_TOKEN="$(curl -sf -X POST "${CASDOOR}/api/login/oauth/access_token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=password' \
  --data-urlencode "username=${ADMIN}" \
  --data-urlencode "password=${ADMIN_PW}" \
  --data-urlencode "client_id=${BUILTIN_CID}" \
  --data-urlencode "client_secret=${BUILTIN_SECRET}" \
  --data-urlencode 'scope=openid' | jq -r '.access_token // empty')"
[ -n "${ADMIN_TOKEN}" ] || { echo "无法获取 Casdoor admin token" >&2; exit 1; }

api_get() {
  curl -sf "${CASDOOR}/api/$1" -H "Authorization: Bearer ${ADMIN_TOKEN}"
}

api_post() {
  local endpoint="$1"
  local payload="$2"
  local response
  response="$(curl -sf -X POST "${CASDOOR}/api/${endpoint}" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" -H 'Content-Type: application/json' -d "${payload}")"
  if [ "$(printf '%s' "${response}" | jq -r '.status // "error"')" != "ok" ]; then
    echo "Casdoor ${endpoint} 失败：$(printf '%s' "${response}" | jq -r '.msg // "unknown error"')" >&2
    return 1
  fi
}

echo "==> 1. 开通独立应用 ${APP}"
APP_JSON="$(api_get "get-application?id=admin/${APP}" | jq -c '.data // empty')"
REDIRECT_JSON="$(printf '%s' "${REDIRECT_URIS}" | jq -R 'split(",") | map(select(length>0))')"
if [ -z "${APP_JSON}" ]; then
  api_post add-application "$(jq -nc --arg org "${ORG}" --arg app "${APP}" --arg cid "${CLIENT_ID}" --arg secret "${CLIENT_SECRET}" --argjson redirects "${REDIRECT_JSON}" '{owner:"admin",name:$app,displayName:"统一权限平台管控台",organization:$org,cert:"cert-built-in",tokenFormat:"JWT",expireInHours:8,refreshExpireInHours:24,enablePassword:true,enableSignUp:false,clientId:$cid,clientSecret:$secret,grantTypes:["authorization_code","refresh_token","password"],redirectUris:$redirects,signinMethods:[{name:"Password",displayName:"Password",rule:"All"}],providers:[]}')"
else
  APP_UPDATED="$(printf '%s' "${APP_JSON}" | jq -c --arg org "${ORG}" --arg cid "${CLIENT_ID}" --argjson redirects "${REDIRECT_JSON}" '
    .organization=$org
    | .clientId=$cid
    | .redirectUris=((.redirectUris // []) + $redirects | unique)
    | .grantTypes=((.grantTypes // []) + ["authorization_code","refresh_token","password"] | unique)
    | .enablePassword=true')"
  api_post "update-application?id=admin/${APP}" "${APP_UPDATED}"
fi

echo "==> 2. 开通组 authz-admin / authz-viewer"
for group in authz-admin authz-viewer; do
  EXIST="$(api_get "get-group?id=${ORG}/${group}" | jq -r '.data.name // empty')"
  if [ -z "${EXIST}" ]; then
    api_post add-group "$(jq -nc --arg owner "${ORG}" --arg name "${group}" '{owner:$owner,name:$name,displayName:$name}')"
  fi
done

echo "==> 3. 把 ${ORG}/${USER_NAME} 加入 authz-admin"
USER_JSON="$(api_get "get-user?id=${ORG}/${USER_NAME}" | jq -c '.data // empty')"
[ -n "${USER_JSON}" ] || { echo "找不到用户 ${ORG}/${USER_NAME}" >&2; exit 1; }
USER_UPDATED="$(printf '%s' "${USER_JSON}" | jq -c --arg group "${ORG}/authz-admin" '.groups = ((.groups // []) + [$group] | unique)')"
api_post "update-user?id=${ORG}/${USER_NAME}" "${USER_UPDATED}"

echo "==> 4. 写入 auth-console/.env.local（不进仓库）"
umask 077
if [ -f "${ENV_FILE}" ]; then
  if grep -q '^VITE_CASDOOR_CLIENT_ID=' "${ENV_FILE}"; then
    tmp="$(mktemp)"
    awk -v cid="${CLIENT_ID}" '
      BEGIN { done=0 }
      /^VITE_CASDOOR_CLIENT_ID=/ { print "VITE_CASDOOR_CLIENT_ID=" cid; done=1; next }
      { print }
      END { if (!done) print "VITE_CASDOOR_CLIENT_ID=" cid }
    ' "${ENV_FILE}" >"${tmp}"
    mv "${tmp}" "${ENV_FILE}"
  else
    printf '\nVITE_CASDOOR_CLIENT_ID=%s\n' "${CLIENT_ID}" >>"${ENV_FILE}"
  fi
else
  cat >"${ENV_FILE}" <<EOF
VITE_CASDOOR_AUTHORITY=http://localhost:8000
VITE_CASDOOR_CLIENT_ID=${CLIENT_ID}
VITE_OIDC_SCOPE=openid profile offline_access
VITE_ADMIN_TARGET=http://localhost:8201
EOF
fi
chmod 600 "${ENV_FILE}"

echo "✅ auth-console 身份开通完成；前端需重启后读取新的 client_id"
