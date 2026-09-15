#!/usr/bin/env bash
# 按能力门户项目，把本机 Casdoor 身份重新开通到当前空库。
# 幂等；交易中心 / WMS 复用各仓库已有的 gitignore 凭据文件，不重置随机口令。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AUTH_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
WORKSPACE="$(cd "${AUTH_ROOT}/.." && pwd)"
# shellcheck source=load-platform-ports.sh
. "${SCRIPT_DIR}/load-platform-ports.sh"
# shellcheck source=casdoor-builtin-app.sh
. "${SCRIPT_DIR}/casdoor-builtin-app.sh"

CASDOOR="${CASDOOR_URL:-http://localhost:8000}"
export CASDOOR_URL="${CASDOOR}"
export CASDOOR_ADMIN="${CASDOOR_ADMIN:-admin}"
export CASDOOR_ADMIN_PW="${CASDOOR_ADMIN_PW:-123}"

provision_tenant() {
  local tenant="$1" user="$2" password="$3"
  TENANT="${tenant}" USER="${user}" PASSWORD="${password}" \
    bash "${SCRIPT_DIR}/casdoor-tenant-provision.sh"
}

echo "==> 校验 Casdoor / Postgres"
docker exec authz-postgres pg_isready -U authz -d spicedb >/dev/null
casdoor_load_builtin_app
casdoor_ensure_builtin_password_grant
echo "   built-in client_id=${BUILTIN_CID}"

echo "==> 统一权限平台管控台"
bash "${SCRIPT_DIR}/auth-console-provision.sh"

echo "==> LangChain4j：org acme/globex"
provision_tenant acme alice 'Alice@12345'
provision_tenant globex bob 'Bob@12345'

echo "==> Recsys：org recsys"
provision_tenant recsys radmin 'Radmin@12345'
provision_tenant recsys rowner1 'Rowner1@12345'

echo "==> Drools 备用组织 beta"
provision_tenant beta carol 'Carol@12345'

echo "==> 权益 / 营销 / 对账 / 风控（shared app）"
BENEFIT_USER=benefit-e2e-admin PASSWORD='Benefit@12345' \
  bash "${SCRIPT_DIR}/benefit-platform-provision.sh"
MARKETING_USER=marketing-e2e-admin PASSWORD='Marketing@12345' \
  bash "${SCRIPT_DIR}/marketing-platform-provision.sh"
RECON_USER=recon-e2e-admin PASSWORD='Recon@12345' \
  bash "${SCRIPT_DIR}/recon-platform-provision.sh"
RISK_USER=risk-e2e-admin PASSWORD='Risk@12345' \
  BANK_CLIENT_SECRET='risk-bank-local-secret-20260914' \
  RUNTIME_CLIENT_SECRET='risk-runtime-local-secret-20260914' \
  bash "${SCRIPT_DIR}/risk-platform-provision.sh"

echo "==> 流程中台独立应用"
WORKFLOW_USER=workflow-admin PASSWORD='Workflow@12345' \
  PHARMACIST_USER=workflow-pharmacist PHARMACIST_PASSWORD='Pharmacist@12345' \
  bash "${SCRIPT_DIR}/workflow-platform-provision.sh"

echo "==> OA 独立应用"
bash "${WORKSPACE}/oa-platform/deploy/scripts/provision-oa-casdoor.sh"

echo "==> 交易中心 / WMS（复用已有随机凭据）"
TRADE_IAM_CREDENTIALS="${WORKSPACE}/transaction-center/deploy/.env.casdoor.json" \
  python3 "${SCRIPT_DIR}/transaction-center-provision.py"
WMS_IAM_CREDENTIALS="${WORKSPACE}/wms-platform/deploy/.env.casdoor.json" \
  python3 "${SCRIPT_DIR}/wms-platform-provision.py"

echo "==> Drools SPA 应用与 act-* 用户"
bash "${WORKSPACE}/drools-demo/scratchpad/casdoor-spa-provision.sh"

echo "==> LangChain4j acme 角色/scope"
ADMIN_TOKEN="$(curl -sf -X POST "${CASDOOR}/api/login/oauth/access_token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=password' \
  --data-urlencode "username=${CASDOOR_ADMIN}" \
  --data-urlencode "password=${CASDOOR_ADMIN_PW}" \
  --data-urlencode "client_id=${BUILTIN_CID}" \
  --data-urlencode "client_secret=${BUILTIN_SECRET}" \
  --data-urlencode 'scope=openid' | jq -r '.access_token // empty')"
[ -n "${ADMIN_TOKEN}" ] || { echo "无法获取 admin token" >&2; exit 1; }

capi() { curl -s -X POST "${CASDOOR}/api/$1" -H "Authorization: Bearer ${ADMIN_TOKEN}" -H 'Content-Type: application/json' -d "$2"; }

ROLE_JSON="$(curl -s "${CASDOOR}/api/get-role?id=acme/admin" -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -c '.data // empty')"
if [ -z "${ROLE_JSON}" ]; then
  capi add-role '{"owner":"acme","name":"admin","displayName":"admin","users":["acme/alice"],"isEnabled":true}' >/dev/null
else
  capi 'update-role?id=acme/admin' "$(printf '%s' "${ROLE_JSON}" | jq -c '.users = (((.users // []) + ["acme/alice"]) | unique) | .isEnabled=true')" >/dev/null
fi
CASDOOR_CLIENT_ID="${BUILTIN_CID}" CASDOOR_CLIENT_SECRET="${BUILTIN_SECRET}" \
  CASDOOR_ORG=acme CASDOOR_MODEL=built-in/user-model-built-in \
  bash "${SCRIPT_DIR}/casdoor-seed.sh"

echo "==> Recsys 组 admins / advertisers"
for group in admins advertisers; do
  EXIST="$(curl -s "${CASDOOR}/api/get-group?id=recsys/${group}" -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -r '.data.name // empty')"
  if [ -z "${EXIST}" ]; then
    capi add-group "$(jq -nc --arg name "${group}" '{owner:"recsys",name:$name,displayName:$name}')" >/dev/null || true
  fi
done
RADMIN="$(curl -s "${CASDOOR}/api/get-user?id=recsys/radmin" -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -c '.data')"
capi 'update-user?id=recsys/radmin' "$(printf '%s' "${RADMIN}" | jq -c '.groups = ((.groups // []) + ["recsys/admins"] | unique)')" >/dev/null
ROWNER="$(curl -s "${CASDOOR}/api/get-user?id=recsys/rowner1" -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -c '.data')"
capi 'update-user?id=recsys/rowner1' "$(printf '%s' "${ROWNER}" | jq -c '.groups = ((.groups // []) + ["recsys/advertisers"] | unique)')" >/dev/null

echo "==> 权益机器身份 benefit-marketing-client（对齐营销 .env 中的 client_id）"
MKT_APP="$(curl -s "${CASDOOR}/api/get-application?id=benefit-center/benefit-marketing-client" -H "Authorization: Bearer ${ADMIN_TOKEN}" | jq -c '.data // empty')"
MKT_SECRET='benefit-marketing-local-client-secret-2026'
if [ -z "${MKT_APP}" ]; then
  capi add-application "$(jq -nc --arg secret "${MKT_SECRET}" '{owner:"benefit-center",name:"benefit-marketing-client",displayName:"benefit-marketing-client",organization:"benefit-center",cert:"cert-built-in",tokenFormat:"JWT",expireInHours:24,refreshExpireInHours:24,enablePassword:false,enableSignUp:false,clientId:"benefit-marketing-client",clientSecret:$secret,grantTypes:["client_credentials"],redirectUris:[],signinMethods:[],providers:[]}')" >/dev/null
else
  capi 'update-application?id=benefit-center/benefit-marketing-client' "$(printf '%s' "${MKT_APP}" | jq -c --arg secret "${MKT_SECRET}" '.clientId="benefit-marketing-client" | .clientSecret=$secret | .grantTypes=["client_credentials"]')" >/dev/null
fi

echo "==> 隐藏 Casdoor 商业菜单"
bash "${SCRIPT_DIR}/casdoor-hide-business.sh"

echo "✅ 门户项目 Casdoor 身份已重建"
