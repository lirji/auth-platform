#!/usr/bin/env bash
# 幂等开通流程中台独立 Casdoor 应用 workflow-console-app-001。
# 用法：
#   WORKFLOW_USER=workflow-admin PASSWORD='本地强口令' bash deploy/workflow-platform-provision.sh
# 可选：PHARMACIST_USER / PHARMACIST_PASSWORD、WORKFLOW_BUSINESS_TENANT（默认 benefit-center）、HIS_TENANT（默认 his）。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-platform-ports.sh
. "${SCRIPT_DIR}/load-platform-ports.sh"
# shellcheck source=casdoor-builtin-app.sh
. "${SCRIPT_DIR}/casdoor-builtin-app.sh"

ORG="workflow-platform"
APP="workflow-console-app-001"
CLIENT_ID="workflow-console-app-001"
USER_NAME="${WORKFLOW_USER:?需要 WORKFLOW_USER（例如 workflow-admin）}"
PASSWORD_VALUE="${PASSWORD:?需要 PASSWORD（不会写入仓库或日志）}"
PHARM_USER="${PHARMACIST_USER:-workflow-pharmacist}"
PHARM_PASSWORD="${PHARMACIST_PASSWORD:-${PASSWORD_VALUE}}"
BUSINESS_TENANT="${WORKFLOW_BUSINESS_TENANT:-benefit-center}"
HIS_TENANT="${HIS_TENANT:-his}"
CASDOOR="${CASDOOR_URL:-http://localhost:8000}"
ADMIN="${CASDOOR_ADMIN:-admin}"
ADMIN_PW="${CASDOOR_ADMIN_PW:-123}"
POSTGRES_CONTAINER="${AUTHZ_POSTGRES_CONTAINER:-authz-postgres}"
CLIENT_SECRET="${WORKFLOW_CLIENT_SECRET:-workflow-console-local-secret-20260914}"
UI_PORT="${WORKFLOW_UI_PORT:-8302}"
REDIRECT_URIS="${REDIRECT_URIS:-http://localhost:${UI_PORT}/callback,http://127.0.0.1:${UI_PORT}/callback,http://localhost:5373/callback,http://127.0.0.1:5373/callback}"

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

echo "==> 1. 开通 organization ${ORG}"
ACCOUNT_ITEMS='[{"name":"Password","visible":true,"viewRule":"Self","modifyRule":"Self"},{"name":"Properties","visible":true,"viewRule":"Self","modifyRule":"Admin"}]'
ORG_JSON="$(api_get "get-organization?id=admin/${ORG}" | jq -c '.data // empty')"
if [ -z "${ORG_JSON}" ]; then
  api_post add-organization "$(jq -nc --arg org "${ORG}" --arg app "${APP}" --argjson items "${ACCOUNT_ITEMS}" '{owner:"admin",name:$org,displayName:"流程审批中台",passwordType:"bcrypt",passwordOptions:["AtLeast6"],defaultApplication:$app,defaultAvatar:"https://cdn.casbin.org/img/casbin.svg",accountItems:$items}')"
else
  ORG_UPDATED="$(printf '%s' "${ORG_JSON}" | jq -c --arg app "${APP}" --argjson items "${ACCOUNT_ITEMS}" '.defaultApplication=$app | .accountItems=$items')"
  api_post "update-organization?id=admin/${ORG}" "${ORG_UPDATED}"
fi

echo "==> 2. 开通独立应用 ${APP}"
APP_JSON="$(api_get "get-application?id=admin/${APP}" | jq -c '.data // empty')"
REDIRECT_JSON="$(printf '%s' "${REDIRECT_URIS}" | jq -R 'split(",") | map(select(length>0))')"
if [ -z "${APP_JSON}" ]; then
  api_post add-application "$(jq -nc --arg org "${ORG}" --arg app "${APP}" --arg cid "${CLIENT_ID}" --arg secret "${CLIENT_SECRET}" --argjson redirects "${REDIRECT_JSON}" '{owner:"admin",name:$app,displayName:"流程审批中台",organization:$org,cert:"cert-built-in",tokenFormat:"JWT-Custom",tokenSigningMethod:"RS256",tokenFields:["Owner","Name","DisplayName","Groups","Properties.tenant_id"],expireInHours:8,refreshExpireInHours:24,enablePassword:true,enableSignUp:false,clientId:$cid,clientSecret:$secret,grantTypes:["authorization_code","refresh_token","password"],redirectUris:$redirects,signinMethods:[{name:"Password",displayName:"Password",rule:"All"}],providers:[]}')"
else
  APP_UPDATED="$(printf '%s' "${APP_JSON}" | jq -c --arg org "${ORG}" --arg cid "${CLIENT_ID}" --argjson redirects "${REDIRECT_JSON}" '
    .organization=$org
    | .clientId=$cid
    | .grantTypes = (((.grantTypes // []) + ["authorization_code","refresh_token","password"]) | unique)
    | .redirectUris = (((.redirectUris // []) + $redirects) | unique)
    | .tokenFormat="JWT-Custom"
    | .tokenFields=["Owner","Name","DisplayName","Groups","Properties.tenant_id"]')"
  api_post "update-application?id=admin/${APP}" "${APP_UPDATED}"
fi

ensure_group() {
  local name="$1"
  local existing
  existing="$(api_get "get-group?id=${ORG}/${name}" | jq -c '.data // empty')"
  if [ -z "${existing}" ]; then
    api_post add-group "$(jq -nc --arg owner "${ORG}" --arg name "${name}" '{owner:$owner,name:$name,displayName:$name}')" || true
  fi
}

echo "==> 3. 开通候选组 ADMIN / PHARMACIST / BENEFIT_SKU_REVIEWER"
ensure_group ADMIN
ensure_group PHARMACIST
ensure_group BENEFIT_SKU_REVIEWER

ensure_user() {
  local name="$1"
  local password="$2"
  local tenant="$3"
  local groups_json="$4"
  local user_json
  user_json="$(api_get "get-user?id=${ORG}/${name}" | jq -c '.data // empty')"
  if [ -z "${user_json}" ]; then
    api_post add-user "$(jq -nc --arg owner "${ORG}" --arg name "${name}" --arg password "${password}" --arg app "${APP}" --arg tenant "${tenant}" --argjson groups "${groups_json}" '{owner:$owner,name:$name,type:"normal-user",displayName:$name,email:($name+"@workflow-platform.local"),password:$password,signupApplication:$app,isAdmin:false,isForbidden:false,isDeleted:false,properties:{tenant_id:$tenant},groups:$groups}')"
  else
    local updated
    updated="$(printf '%s' "${user_json}" | jq -c --arg tenant "${tenant}" --arg app "${APP}" --argjson groups "${groups_json}" '
      .properties = ((.properties // {}) + {tenant_id:$tenant})
      | .groups = $groups
      | .signupApplication=$app')"
    api_post "update-user?id=${ORG}/${name}" "${updated}"
  fi
  curl -sf -X POST "${CASDOOR}/api/set-password" -H "Authorization: Bearer ${ADMIN_TOKEN}" \
    --data-urlencode "userOwner=${ORG}" --data-urlencode "userName=${name}" \
    --data-urlencode 'oldPassword=' --data-urlencode "newPassword=${password}" >/dev/null
}

echo "==> 4. 开通用户 ${USER_NAME} / ${PHARM_USER}"
ensure_user "${USER_NAME}" "${PASSWORD_VALUE}" "${BUSINESS_TENANT}" \
  "$(jq -nc --arg org "${ORG}" '[$org+"/ADMIN",$org+"/BENEFIT_SKU_REVIEWER"]')"
ensure_user "${PHARM_USER}" "${PHARM_PASSWORD}" "${HIS_TENANT}" \
  "$(jq -nc --arg org "${ORG}" '[$org+"/PHARMACIST"]')"

echo "==> 5. 验证 password grant"
TOKEN="$(curl -sf -X POST "${CASDOOR}/api/login/oauth/access_token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=password' \
  --data-urlencode "username=${USER_NAME}" \
  --data-urlencode "password=${PASSWORD_VALUE}" \
  --data-urlencode "client_id=${CLIENT_ID}" \
  --data-urlencode "client_secret=${CLIENT_SECRET}" \
  --data-urlencode 'scope=openid profile offline_access' | jq -r '.access_token // empty')"
[ -n "${TOKEN}" ] || { echo "流程中台管理员无法换取 access token" >&2; exit 1; }

PAYLOAD="$(printf '%s' "${TOKEN}" | cut -d. -f2 | tr '_-' '/+')"
PADDING=$(( (4-${#PAYLOAD}%4)%4 ))
CLAIMS="$(printf '%s%*s' "${PAYLOAD}" "${PADDING}" '' | tr ' ' '=' | base64 -d 2>/dev/null)"
printf '%s' "${CLAIMS}" | jq -e --arg org "${ORG}" --arg cid "${CLIENT_ID}" --arg tenant "${BUSINESS_TENANT}" '
  .owner == $org
  and .tenant_id == $tenant
  and ((.aud | if type=="array" then . else [.] end) | index($cid) != null)' >/dev/null

echo "✅ workflow-platform 身份开通完成"
echo "   login_org=${ORG} client_id=${CLIENT_ID}"
echo "   ${USER_NAME} tenant_id=${BUSINESS_TENANT} 组=ADMIN,BENEFIT_SKU_REVIEWER"
echo "   ${PHARM_USER} tenant_id=${HIS_TENANT} 组=PHARMACIST"
