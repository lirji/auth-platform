#!/usr/bin/env bash
# 在统一 Casdoor 中幂等开通 marketing-platform：organization、shared-app 回调、用户、
# 角色(viewer/operator/admin)、营销控制台 resource:action 权限。
# 身份 + 粗粒度 scope，无机器身份 / 无 SpiceDB。
# 密码必须由调用方通过 PASSWORD 注入；脚本不打印密码、client secret 或 access token。
#
# 用法：
#   MARKETING_USER=marketing-e2e-admin PASSWORD='本地强口令' bash deploy/marketing-platform-provision.sh
# 可选：CASDOOR_URL、CASDOOR_ADMIN、CASDOOR_ADMIN_PW、AUTHZ_POSTGRES_CONTAINER、
#       SHARED_APP、SHARED_CLIENT_ID、SHARED_CLIENT_SECRET、REDIRECT_URIS。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-platform-ports.sh
. "${SCRIPT_DIR}/load-platform-ports.sh"

TENANT="marketing-platform"
USER_NAME="${MARKETING_USER:?需要 MARKETING_USER（例如 marketing-e2e-admin）}"
PASSWORD_VALUE="${PASSWORD:?需要 PASSWORD（不会写入仓库或日志）}"
CASDOOR="${CASDOOR_URL:-http://localhost:8000}"
ADMIN="${CASDOOR_ADMIN:-admin}"
ADMIN_PW="${CASDOOR_ADMIN_PW:-123}"
POSTGRES_CONTAINER="${AUTHZ_POSTGRES_CONTAINER:-authz-postgres}"
BUILTIN_CID="${BUILTIN_CID:-ea46d9a8033b0be2d8ed}"
SHARED_APP="${SHARED_APP:-rag-shared}"
SHARED_CID="${SHARED_CLIENT_ID:-ragshared0client00000001}"
SHARED_CSEC="${SHARED_CLIENT_SECRET:-ragshared0secret000000000000000001}"
CLIENT_ID="${SHARED_CID}-org-${TENANT}"
REDIRECT_URIS="${REDIRECT_URIS:-http://localhost:${MARKETING_UI_PORT}/auth/callback,http://127.0.0.1:${MARKETING_UI_PORT}/auth/callback}"

for command_name in curl jq docker; do
  command -v "${command_name}" >/dev/null || { echo "缺少命令：${command_name}" >&2; exit 1; }
done

BUILTIN_SECRET="$(docker exec "${POSTGRES_CONTAINER}" psql -U authz -d spicedb -tAc \
  "select client_secret from application where client_id='${BUILTIN_CID}'" 2>/dev/null | tr -d '[:space:]')"
[ -n "${BUILTIN_SECRET}" ] || { echo "无法读取 built-in client secret" >&2; exit 1; }

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

echo "==> 1. 校准 shared application 回调白名单"
APP_JSON="$(api_get "get-application?id=admin/${SHARED_APP}" | jq -c '.data // empty')"
[ -n "${APP_JSON}" ] || { echo "shared application ${SHARED_APP} 不存在，请先运行 casdoor-tenant-provision.sh" >&2; exit 1; }
REDIRECT_JSON="$(printf '%s' "${REDIRECT_URIS}" | jq -R 'split(",") | map(select(length>0))')"
APP_UPDATED="$(printf '%s' "${APP_JSON}" | jq -c --argjson redirects "${REDIRECT_JSON}" '
  .redirectUris = (((.redirectUris // []) + $redirects) | unique)
  | .grantTypes = (((.grantTypes // []) + ["authorization_code","refresh_token","password"]) | unique)
  | .isShared = true
  | .orgChoiceMode = "Input"')"
api_post "update-application?id=admin/${SHARED_APP}" "${APP_UPDATED}"

echo "==> 2. 开通 organization ${TENANT}"
ORG_JSON="$(api_get "get-organization?id=admin/${TENANT}" | jq -c '.data // empty')"
if [ -z "${ORG_JSON}" ]; then
  api_post add-organization "$(jq -nc --arg tenant "${TENANT}" --arg app "${SHARED_APP}" '{owner:"admin",name:$tenant,displayName:"营销低代码平台",passwordType:"bcrypt",passwordOptions:["AtLeast6"],defaultApplication:$app,defaultAvatar:"https://cdn.casbin.org/img/casbin.svg",accountItems:[{name:"Password",visible:true,viewRule:"Self",modifyRule:"Self"}]}')"
else
  ORG_UPDATED="$(printf '%s' "${ORG_JSON}" | jq -c --arg app "${SHARED_APP}" '.defaultApplication=$app')"
  api_post "update-organization?id=admin/${TENANT}" "${ORG_UPDATED}"
fi

echo "==> 3. 开通用户 ${USER_NAME}@${TENANT}"
USER_JSON="$(api_get "get-user?id=${TENANT}/${USER_NAME}" | jq -c '.data // empty')"
if [ -z "${USER_JSON}" ]; then
  api_post add-user "$(jq -nc --arg tenant "${TENANT}" --arg user "${USER_NAME}" --arg password "${PASSWORD_VALUE}" --arg app "${SHARED_APP}" '{owner:$tenant,name:$user,type:"normal-user",displayName:$user,email:($user+"@marketing-platform.local"),phone:"",password:$password,signupApplication:$app,isAdmin:false,isForbidden:false,isDeleted:false,properties:{}}')"
fi
curl -sf -X POST "${CASDOOR}/api/set-password" -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  --data-urlencode "userOwner=${TENANT}" --data-urlencode "userName=${USER_NAME}" \
  --data-urlencode 'oldPassword=' --data-urlencode "newPassword=${PASSWORD_VALUE}" >/dev/null

USER_JSON="$(api_get "get-user?id=${TENANT}/${USER_NAME}" | jq -c '.data // empty')"
SUBJECT="$(printf '%s' "${USER_JSON}" | jq -r '.id // empty')"
[ -n "${SUBJECT}" ] || { echo "无法读取新用户 sub" >&2; exit 1; }
USER_REF="${TENANT}/${USER_NAME}"

echo "==> 4. 创建角色 viewer/operator/admin，并把测试用户加入 admin"
for role in viewer operator admin; do
  ROLE_JSON="$(api_get "get-role?id=${TENANT}/${role}" | jq -c '.data // empty')"
  if [ -z "${ROLE_JSON}" ]; then
    ROLE_USERS='[]'
    if [ "${role}" = 'admin' ]; then ROLE_USERS="$(jq -nc --arg user "${USER_REF}" '[$user]')"; fi
    api_post add-role "$(jq -nc --arg owner "${TENANT}" --arg role "${role}" --argjson users "${ROLE_USERS}" '{owner:$owner,name:$role,displayName:$role,users:$users,isEnabled:true}')"
  elif [ "${role}" = 'admin' ]; then
    ROLE_UPDATED="$(printf '%s' "${ROLE_JSON}" | jq -c --arg user "${USER_REF}" '.users = (((.users // []) + [$user]) | unique) | .isEnabled=true')"
    api_post "update-role?id=${TENANT}/${role}" "${ROLE_UPDATED}"
  fi
done

echo "==> 5. 重建 marketing permissions（Casdoor 为 role->permission 真相源）"
# 角色累积: viewer ⊂ operator ⊂ admin。marketing.admin 在消费方展开为通配权限。
PERMISSION_MATRIX=$(cat <<'EOF'
campaign.read|viewer,operator,admin
definition.read|viewer,operator,admin
audience.read|viewer,operator,admin
audience.preview|viewer,operator,admin
audience-field.read|viewer,operator,admin
journey.read|viewer,operator,admin
benefit.read|viewer,operator,admin
template.read|viewer,operator,admin
measurement.read|viewer,operator,admin
release.read|viewer,operator,admin
trace.read|viewer,operator,admin
contact.read|viewer,operator,admin
event.read|viewer,operator,admin
funding.account-read|viewer,operator,admin
campaign.write|operator,admin
definition.write|operator,admin
definition.submit|operator,admin
audience.write|operator,admin
audience.snapshot|operator,admin
benefit.write|operator,admin
measurement.ingest|operator,admin
event.replay|operator,admin
funding.reconcile|operator,admin
release.activate|operator,admin
approval.business|admin
approval.finance|admin
approval.compliance|admin
approval.merchant|admin
release.rollback|admin
release.kill-switch|admin
marketing.admin|admin
EOF
)

while IFS='|' read -r permission role_csv; do
  [ -n "${permission}" ] || continue
  ROLES_JSON="$(printf '%s' "${role_csv}" | tr ',' '\n' | jq -R --arg tenant "${TENANT}" '$tenant+"/"+.' | jq -s '.')"
  curl -sf -X POST "${CASDOOR}/api/delete-permission" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg owner "${TENANT}" --arg name "${permission}" '{owner:$owner,name:$name}')" >/dev/null || true
  api_post add-permission "$(jq -nc --arg owner "${TENANT}" --arg name "${permission}" --argjson roles "${ROLES_JSON}" '{owner:$owner,name:$name,displayName:$name,model:"built-in/user-model-built-in",resourceType:"Custom",resources:["marketing-platform"],actions:["Read"],effect:"Allow",roles:$roles,isEnabled:true}')"
done <<< "${PERMISSION_MATRIX}"

echo "==> 6. 用真实 password grant 验证用户 token claim（owner/sub/aud/permissions）"
TOKEN="$(curl -sf -X POST "${CASDOOR}/api/login/oauth/access_token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=password' \
  --data-urlencode "username=${USER_NAME}" \
  --data-urlencode "password=${PASSWORD_VALUE}" \
  --data-urlencode "client_id=${CLIENT_ID}" \
  --data-urlencode "client_secret=${SHARED_CSEC}" \
  --data-urlencode 'scope=openid profile offline_access' | jq -r '.access_token // empty')"
[ -n "${TOKEN}" ] || { echo "测试用户无法换取 access token" >&2; exit 1; }

PAYLOAD="$(printf '%s' "${TOKEN}" | cut -d. -f2 | tr '_-' '/+')"
PADDING=$(( (4-${#PAYLOAD}%4)%4 ))
CLAIMS="$(printf '%s%*s' "${PAYLOAD}" "${PADDING}" '' | tr ' ' '=' | base64 -d 2>/dev/null)"

printf '%s' "${CLAIMS}" | jq -e --arg tenant "${TENANT}" --arg cid "${CLIENT_ID}" --arg sub "${SUBJECT}" '
  .owner == $tenant
  and .sub == $sub
  and ((.aud | if type=="array" then . else [.] end) | index($cid) != null)
  and ((.permissions // []) | map(if type=="object" then .name else . end) | index("marketing.admin") != null)
  and ((.permissions // []) | map(if type=="object" then .name else . end) | index("campaign.read") != null)' >/dev/null

PERMISSION_COUNT="$(printf '%s' "${CLAIMS}" | jq '(.permissions // []) | length')"
JWKS_URI="${CASDOOR}/.well-known/jwks"
echo "✅ marketing-platform 统一身份开通并验证完成"
echo "   tenant=${TENANT} user=${USER_NAME} sub=${SUBJECT} client_id=${CLIENT_ID} permissions=${PERMISSION_COUNT}"
echo ""
echo "   后端 JWT 模式 env（填入 marketing-lowcode-platform 部署；容器内 issuer 改 host.docker.internal）："
echo "     MARKETING_SECURITY_MODE=OIDC"
echo "     OIDC_ISSUER_URI=http://host.docker.internal:8000"
echo "     OIDC_AUDIENCE=${CLIENT_ID}"
echo ""
echo "   前端 console env（登录用；client 无 secret）："
echo "     CONSOLE_AUTH_MODE=OIDC"
echo "     CONSOLE_OIDC_AUTHORITY=${CASDOOR}"
echo "     CONSOLE_OIDC_CLIENT_ID=${CLIENT_ID}"
echo "     CONSOLE_OIDC_ORGANIZATION=${TENANT}"
echo "     CONSOLE_OIDC_SCOPE=openid profile offline_access"
echo "     CONSOLE_OIDC_ORIGIN=${CASDOOR}"
echo ""
echo "   本地 Compose 叠加层：bash deploy/compose.sh --secure up -d --build"
