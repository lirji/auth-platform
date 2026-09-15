#!/usr/bin/env bash
# 在统一 Casdoor 中幂等开通 benefit-center：organization、shared-app 回调、用户、角色(viewer/operator/admin)、
# 权限(benefit.award.read/write、benefit.remediate、benefit.admin)。对齐 recon-platform-provision.sh：
# 身份 + 粗粒度 scope，无机器身份 / 无 SpiceDB。
# 密码必须由调用方通过 PASSWORD 注入；脚本不打印密码、client secret 或 access token。
#
# 用法：
#   BENEFIT_USER=benefit-e2e-admin PASSWORD='本地强口令' bash deploy/benefit-platform-provision.sh
# 可选：CASDOOR_URL、CASDOOR_ADMIN、CASDOOR_ADMIN_PW、AUTHZ_POSTGRES_CONTAINER、
#       SHARED_APP、SHARED_CLIENT_ID、SHARED_CLIENT_SECRET、REDIRECT_URIS、
#       BENEFIT_BUSINESS_TENANT（货主业务租户，默认 dev-tenant）。
# 登录组织固定为 benefit-center；货主必须通过 properties.tenant_id 单独表达，不能回退到 owner。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=load-platform-ports.sh
. "${SCRIPT_DIR}/load-platform-ports.sh"

TENANT="benefit-center"
BUSINESS_TENANT="${BENEFIT_BUSINESS_TENANT:-dev-tenant}"
USER_NAME="${BENEFIT_USER:?需要 BENEFIT_USER（例如 benefit-e2e-admin）}"
PASSWORD_VALUE="${PASSWORD:?需要 PASSWORD（不会写入仓库或日志）}"
CASDOOR="${CASDOOR_URL:-http://localhost:8000}"
ADMIN="${CASDOOR_ADMIN:-admin}"
ADMIN_PW="${CASDOOR_ADMIN_PW:-123}"
POSTGRES_CONTAINER="${AUTHZ_POSTGRES_CONTAINER:-authz-postgres}"
SHARED_APP="${SHARED_APP:-rag-shared}"
SHARED_CID="${SHARED_CLIENT_ID:-ragshared0client00000001}"
SHARED_CSEC="${SHARED_CLIENT_SECRET:-ragshared0secret000000000000000001}"
CLIENT_ID="${SHARED_CID}-org-${TENANT}"
# 统一入口与门户保持同端口；回调路径固定 /auth/callback。
REDIRECT_URIS="${REDIRECT_URIS:-http://localhost:${BENEFIT_UI_PORT}/auth/callback,http://127.0.0.1:${BENEFIT_UI_PORT}/auth/callback}"

for command_name in curl jq docker; do
  command -v "${command_name}" >/dev/null || { echo "缺少命令：${command_name}" >&2; exit 1; }
done

# shellcheck source=casdoor-builtin-app.sh
. "${SCRIPT_DIR}/casdoor-builtin-app.sh"
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
  api_post add-organization "$(jq -nc --arg tenant "${TENANT}" --arg app "${SHARED_APP}" '{owner:"admin",name:$tenant,displayName:$tenant,passwordType:"bcrypt",passwordOptions:["AtLeast6"],defaultApplication:$app,defaultAvatar:"https://cdn.casbin.org/img/casbin.svg",accountItems:[{name:"Password",visible:true,viewRule:"Self",modifyRule:"Self"}]}')"
else
  ORG_UPDATED="$(printf '%s' "${ORG_JSON}" | jq -c --arg app "${SHARED_APP}" '.defaultApplication=$app')"
  api_post "update-organization?id=admin/${TENANT}" "${ORG_UPDATED}"
fi

echo "==> 3. 开通用户 ${USER_NAME}@${TENANT}"
USER_JSON="$(api_get "get-user?id=${TENANT}/${USER_NAME}" | jq -c '.data // empty')"
if [ -z "${USER_JSON}" ]; then
  api_post add-user "$(jq -nc --arg tenant "${TENANT}" --arg businessTenant "${BUSINESS_TENANT}" --arg user "${USER_NAME}" --arg password "${PASSWORD_VALUE}" --arg app "${SHARED_APP}" '{owner:$tenant,name:$user,type:"normal-user",displayName:$user,email:($user+"@benefit-center.local"),phone:"",password:$password,signupApplication:$app,isAdmin:false,isForbidden:false,isDeleted:false,properties:{tenant_id:$businessTenant}}')"
fi
curl -sf -X POST "${CASDOOR}/api/set-password" -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  --data-urlencode "userOwner=${TENANT}" --data-urlencode "userName=${USER_NAME}" \
  --data-urlencode 'oldPassword=' --data-urlencode "newPassword=${PASSWORD_VALUE}" >/dev/null

USER_JSON="$(api_get "get-user?id=${TENANT}/${USER_NAME}" | jq -c '.data // empty')"
# 已存在的用户也必须幂等校准货主属性；owner 继续只表示登录组织。
USER_UPDATED="$(printf '%s' "${USER_JSON}" | jq -c --arg businessTenant "${BUSINESS_TENANT}" \
  '.properties = ((.properties // {}) + {tenant_id:$businessTenant})')"
api_post "update-user?id=${TENANT}/${USER_NAME}" "${USER_UPDATED}"
USER_JSON="$(api_get "get-user?id=${TENANT}/${USER_NAME}" | jq -c '.data // empty')"
printf '%s' "${USER_JSON}" | jq -e --arg tenant "${TENANT}" --arg businessTenant "${BUSINESS_TENANT}" '
  .owner == $tenant
  and .properties.tenant_id == $businessTenant' >/dev/null
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

echo "==> 5. 重建 benefit permissions（Casdoor 为 role->permission 真相源）"
# 角色累积:viewer⊂operator⊂admin。运营台路由需 benefit.admin；补发写需 benefit.remediate。
PERMISSION_MATRIX=$(cat <<'EOF'
benefit.award.read|viewer,operator,admin
benefit.award.write|admin
benefit.remediate|operator,admin
benefit.admin|admin
EOF
)

while IFS='|' read -r permission role_csv; do
  [ -n "${permission}" ] || continue
  ROLES_JSON="$(printf '%s' "${role_csv}" | tr ',' '\n' | jq -R --arg tenant "${TENANT}" '$tenant+"/"+.' | jq -s '.')"
  curl -sf -X POST "${CASDOOR}/api/delete-permission" \
    -H "Authorization: Bearer ${ADMIN_TOKEN}" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg owner "${TENANT}" --arg name "${permission}" '{owner:$owner,name:$name}')" >/dev/null || true
  api_post add-permission "$(jq -nc --arg owner "${TENANT}" --arg name "${permission}" --argjson roles "${ROLES_JSON}" '{owner:$owner,name:$name,displayName:$name,model:"built-in/user-model-built-in",resourceType:"Custom",resources:["benefit-center"],actions:["Read"],effect:"Allow",roles:$roles,isEnabled:true}')"
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

printf '%s' "${CLAIMS}" | jq -e --arg tenant "${TENANT}" --arg businessTenant "${BUSINESS_TENANT}" --arg cid "${CLIENT_ID}" --arg sub "${SUBJECT}" '
  .owner == $tenant
  and .properties.tenant_id == $businessTenant
  and .sub == $sub
  and ((.aud | if type=="array" then . else [.] end) | index($cid) != null)
  and ((.permissions // []) | map(if type=="object" then .name else . end) | index("benefit.admin") != null)
  and ((.permissions // []) | map(if type=="object" then .name else . end) | index("benefit.award.read") != null)' >/dev/null

PERMISSION_COUNT="$(printf '%s' "${CLAIMS}" | jq '(.permissions // []) | length')"
JWKS_URI="${CASDOOR}/.well-known/jwks"
echo "✅ benefit-center 统一身份开通并验证完成"
echo "   login_org=${TENANT} business_tenant=${BUSINESS_TENANT} user=${USER_NAME} sub=${SUBJECT} client_id=${CLIENT_ID} permissions=${PERMISSION_COUNT}"
echo ""
echo "   后端 JWT 模式 env（填入 benefit-center 部署；容器内 JWKS 改 host.docker.internal）："
echo "     BENEFIT_SECURITY_DEV_MODE=false"
echo "     BENEFIT_JWK_SET_URI=${JWKS_URI}"
echo "     BENEFIT_AUDIENCE_TENANTS=${CLIENT_ID}=${TENANT}"
echo ""
echo "   前端 benefit-console env（VITE_*，登录用；client 无 secret）："
echo "     VITE_AUTH_MODE=oidc"
echo "     VITE_CASDOOR_SERVER_URL=${CASDOOR}"
echo "     VITE_CASDOOR_CLIENT_ID=${CLIENT_ID}"
echo "     VITE_CASDOOR_ORGANIZATION=${TENANT}"
echo "     VITE_CASDOOR_APP_NAME=${SHARED_APP}"
echo ""
echo "   本地 Compose 叠加层：bash deploy/compose.sh --secure up -d --build"
