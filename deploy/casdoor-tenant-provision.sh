#!/usr/bin/env bash
# casdoor-tenant-provision.sh — 造/补 Casdoor 租户身份（org + user），可选联动 SpiceDB 成员组。
# -----------------------------------------------------------------------------
# 背景（方案 C：Casdoor Shared Application + 选组织）：一个租户 = 一个 Casdoor organization
#   （edge 把 token 的 owner→tenantId、sub→userId），但所有租户共用同一个 Shared Application
#   （built-in 拥有、isShared=true、orgChoiceMode=Input）。登录/取 token 时用派生 client_id
#   `<base>-org-<tenant>`（同一 client_secret），Casdoor 会签出 aud=[`<base>-org-<tenant>`]、owner=该 org。
#   edge 的 CASDOOR_AUDIENCES 只需一次性白名单 base（ragshared0client00000001）——它按
#   `<base>-org-*` 家族 + (owner,aud) 绑定放行，故新增租户对 edge 零改动、无需重启（这正是 shared app 的意义；
#   对比旧「每租户一个 app」模型每次都要改 audiences + 重启 edge）。
# 本脚本把「(可选)确保 shared app 存在 → 建 org → 建 user → 设密码 →(可选)写 SpiceDB 成员组」一条龙做掉，幂等可重复跑。
#
# 用法：
#   TENANT=acme USER=alice PASSWORD='Alice@12345' WIRE_SPICEDB=1 bash deploy/casdoor-tenant-provision.sh
#   TENANT=beta USER=carol PASSWORD='Carol@12345' WIRE_SPICEDB=1 bash deploy/casdoor-tenant-provision.sh
#
# 参数(env)：
#   TENANT(必填)   租户 = Casdoor org 名（token owner→tenantId；对象 id 前缀 <tenant>_）
#   USER(必填)     用户名（登录用户名 = token name）
#   PASSWORD(必填) 口令（org 策略默认 AtLeast6）
#   WIRE_SPICEDB   =1 时把该用户 sub(UUID) 加进 SpiceDB group:<tenant>_<MEMBERS_GROUP> 并补
#                  space:<tenant>_<DEFAULT_SPACE>#viewer@group:<tenant>_<MEMBERS_GROUP>#member（D3① 同租户默认可见）
#   MEMBERS_GROUP  成员组名，默认 members
#   DEFAULT_SPACE  默认空间名，默认 default
#   ENSURE_SHARED_APP =1(默认) 时若 shared app 不存在则幂等创建（greenfield 可复现）；已存在则原样不动
#   SHARED_APP        shared application 名，默认 rag-shared
#   SHARED_CLIENT_ID  base client_id，默认 ragshared0client00000001（须 = edge CASDOOR_AUDIENCES 的 base、前端 VITE_CASDOOR_CLIENT_ID）
#   SHARED_CLIENT_SECRET  默认 ragshared0secret000000000000000001（所有 <base>-org-<tenant> 共用此 secret）
#   SHARED_REDIRECT_URIS  逗号分隔的回调白名单，默认覆盖 :8093(nginx) + :5173/:5273(vite dev) 的 callback/login/oidc-silent
#   CASDOOR_URL(默认 http://localhost:8000) BUILTIN_CID(默认 ea46d9a8033b0be2d8ed，用于取 admin token)
#   CASDOOR_ADMIN/CASDOOR_ADMIN_PW(默认 admin/123)
#   SPICEDB_HTTP(默认 http://localhost:8543) SPICEDB_KEY(默认 authz_dev_key)
#
# 产出：打印该租户的派生 client_id(`<base>-org-<tenant>`)、shared client_secret 与 user 的 sub(UUID)。
# shared app 模型下新增租户无需动 edge：只要 edge 的 CASDOOR_AUDIENCES 已含 base，家族 aud 自动放行。
# 依赖：curl + jq + docker（查 built-in app 的 client_secret 取 admin token；ENSURE_SHARED_APP 探测 shared app）。注释一律中文。
# 注：echo/heredoc 里凡 ${VAR} 紧邻中文全角括号一律用花括号，避免 set -u 把全角字节并进变量名误判 unbound。
set -uo pipefail

TENANT="${TENANT:?需要 TENANT（= Casdoor org 名）}"
USER="${USER:?需要 USER}"
PASSWORD="${PASSWORD:?需要 PASSWORD}"
MEMBERS_GROUP="${MEMBERS_GROUP:-members}"
DEFAULT_SPACE="${DEFAULT_SPACE:-default}"
WIRE_SPICEDB="${WIRE_SPICEDB:-0}"
ENSURE_SHARED_APP="${ENSURE_SHARED_APP:-1}"
CASDOOR="${CASDOOR_URL:-http://localhost:8000}"
BUILTIN_CID="${BUILTIN_CID:-ea46d9a8033b0be2d8ed}"
ADMIN="${CASDOOR_ADMIN:-admin}"; ADMIN_PW="${CASDOOR_ADMIN_PW:-123}"
SPICEDB_HTTP="${SPICEDB_HTTP:-http://localhost:8543}"
SPICEDB_KEY="${SPICEDB_KEY:-authz_dev_key}"
SHARED_APP="${SHARED_APP:-rag-shared}"
SHARED_CID="${SHARED_CLIENT_ID:-ragshared0client00000001}"
SHARED_CSEC="${SHARED_CLIENT_SECRET:-ragshared0secret000000000000000001}"
SHARED_REDIRECT_URIS="${SHARED_REDIRECT_URIS:-http://localhost:8093/callback,http://localhost:8093/login,http://localhost:8093/oidc-silent,http://localhost:5173/callback,http://localhost:5173/login,http://localhost:5173/oidc-silent,http://localhost:5273/callback,http://localhost:5273/login,http://localhost:5273/oidc-silent}"
# 该租户实际登录/取 token 用的派生 client_id（同一 shared secret）。
CID="${SHARED_CID}-org-${TENANT}"

command -v jq >/dev/null || { echo "需要 jq" >&2; exit 1; }

# built-in app 的 secret 不入库，现查 Postgres 取 admin token
BSEC=$(docker exec authz-postgres psql -U authz -d spicedb -tAc \
  "select client_secret from application where client_id='${BUILTIN_CID}'" 2>/dev/null | tr -d '[:space:]')
AT=$(curl -s -X POST "${CASDOOR}/api/login/oauth/access_token" -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&username=${ADMIN}&password=${ADMIN_PW}&client_id=${BUILTIN_CID}&client_secret=${BSEC}&scope=openid" \
  | jq -r '.access_token // empty')
[ -n "${AT}" ] || { echo "拿不到 Casdoor admin token（检查 admin/123 与 built-in app 凭据）" >&2; exit 1; }
capi(){ curl -s -X POST "${CASDOOR}/api/$1" -H "Authorization: Bearer ${AT}" -H "Content-Type: application/json" -d "$2"; }

# ── 0.（可选）确保 Shared Application 存在（幂等）。已存在则原样不动，避免覆盖线上 redirectUris/grantTypes。──
if [ "${ENSURE_SHARED_APP}" = "1" ]; then
  EXIST=$(curl -s "${CASDOOR}/api/get-application?id=admin/${SHARED_APP}" -H "Authorization: Bearer ${AT}" | jq -r '.data.name // empty')
  if [ -n "${EXIST}" ]; then
    echo "==> 0. shared app ${SHARED_APP} 已存在，跳过创建"
  else
    echo "==> 0. 创建 shared app ${SHARED_APP}（built-in 拥有, isShared=true, orgChoiceMode=Input, base=${SHARED_CID}）"
    RU_JSON=$(printf '%s' "${SHARED_REDIRECT_URIS}" | jq -R 'split(",")')
    capi add-application "{\"owner\":\"admin\",\"name\":\"${SHARED_APP}\",\"displayName\":\"${SHARED_APP}\",\"organization\":\"built-in\",\"isShared\":true,\"orgChoiceMode\":\"Input\",\"cert\":\"cert-built-in\",\"tokenFormat\":\"JWT\",\"expireInHours\":168,\"refreshExpireInHours\":168,\"enablePassword\":true,\"enableSignUp\":true,\"clientId\":\"${SHARED_CID}\",\"clientSecret\":\"${SHARED_CSEC}\",\"grantTypes\":[\"authorization_code\",\"refresh_token\",\"password\"],\"redirectUris\":${RU_JSON},\"signinMethods\":[{\"name\":\"Password\",\"displayName\":\"Password\",\"rule\":\"All\"}],\"providers\":[]}" \
      | jq -c '{status,msg}'
  fi
fi

echo "==> 1. org ${TENANT}（defaultApplication=${SHARED_APP}）"
# defaultApplication 必设：Casdoor 控制台的 /login/<org> 页（未登录访问控制台按 lastLoginOrg 重定向、
# 控制台登出回跳都会命中）靠 get-default-application 渲染登录表单；org 没设该字段且名下无自有 app
# （shared app 归 built-in 所有，按 organization=<org> 兜底查询查不到）时接口报错 → 前端跳 /404。
capi add-organization "{\"owner\":\"admin\",\"name\":\"${TENANT}\",\"displayName\":\"${TENANT}\",\"passwordType\":\"bcrypt\",\"passwordOptions\":[\"AtLeast6\"],\"defaultApplication\":\"${SHARED_APP}\",\"defaultAvatar\":\"https://cdn.casbin.org/img/casbin.svg\",\"accountItems\":[{\"name\":\"Password\",\"visible\":true,\"viewRule\":\"Self\",\"modifyRule\":\"Self\"}]}" \
  | jq -c '{status,msg}'
# 幂等回填：org 已存在（add 报 already exists）但 defaultApplication 为空时补上，老租户重跑本脚本即自愈。
ORG_JSON=$(curl -s "${CASDOOR}/api/get-organization?id=admin/${TENANT}" -H "Authorization: Bearer ${AT}" | jq -c '.data // empty')
if [ -n "${ORG_JSON}" ] && [ "$(printf '%s' "${ORG_JSON}" | jq -r '.defaultApplication // ""')" = "" ]; then
  echo "   org 已存在且 defaultApplication 为空，回填为 ${SHARED_APP}"
  capi "update-organization?id=admin/${TENANT}" "$(printf '%s' "${ORG_JSON}" | jq -c --arg app "${SHARED_APP}" '.defaultApplication=$app')" \
    | jq -c '{status,msg}'
fi

echo "==> 2. user ${USER}@${TENANT}（signupApplication=${SHARED_APP}）+ 设密码"
capi add-user "{\"owner\":\"${TENANT}\",\"name\":\"${USER}\",\"type\":\"normal-user\",\"displayName\":\"${USER}\",\"email\":\"${USER}@${TENANT}.local\",\"phone\":\"\",\"password\":\"${PASSWORD}\",\"signupApplication\":\"${SHARED_APP}\",\"isAdmin\":false,\"isForbidden\":false,\"isDeleted\":false,\"properties\":{}}" \
  | jq -c '{status,msg}'
# set-password 保证按 org bcrypt 落盘（add-user 已带 password 时这步可能报"与当前相同"，无害）
curl -s -X POST "${CASDOOR}/api/set-password" -H "Authorization: Bearer ${AT}" \
  --data-urlencode "userOwner=${TENANT}" --data-urlencode "userName=${USER}" \
  --data-urlencode "oldPassword=" --data-urlencode "newPassword=${PASSWORD}" >/dev/null 2>&1 || true
UID_=$(curl -s "${CASDOOR}/api/get-user?id=${TENANT}/${USER}" -H "Authorization: Bearer ${AT}" | jq -r '.data.id')
[ -n "${UID_}" ] && [ "${UID_}" != "null" ] || { echo "读不到 ${USER} 的 UUID" >&2; exit 1; }

echo "==> 3. 验证 password grant + claims（client_id=${CID}，shared secret）"
TOK=$(curl -s -X POST "${CASDOOR}/api/login/oauth/access_token" -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&username=${USER}&password=${PASSWORD}&client_id=${CID}&client_secret=${SHARED_CSEC}&scope=openid profile" \
  | jq -r '.access_token // empty')
if [ -n "${TOK}" ]; then
  CLAIMS=$(printf '%s' "${TOK}" | cut -d. -f2 | tr '_-' '/+' | { b=$(cat); p=$(( (4-${#b}%4)%4 )); printf '%s%*s' "${b}" "${p}" '' | tr ' ' '='; } | base64 -d 2>/dev/null | jq -c '{iss,aud,sub,owner,name}')
  echo "   token OK: ${CLAIMS}"
else
  echo "   password grant 失败（该用户暂不可登录；确认 shared app 开了 password grant 且 CID=${CID}）" >&2
fi

if [ "${WIRE_SPICEDB}" = "1" ]; then
  echo "==> 4. SpiceDB: user:${UID_} → group:${TENANT}_${MEMBERS_GROUP} + space viewer 绑定"
  curl -s -X POST "${SPICEDB_HTTP}/v1/relationships/write" -H "Authorization: Bearer ${SPICEDB_KEY}" -H "Content-Type: application/json" \
    -d "{\"updates\":[
      {\"operation\":\"OPERATION_TOUCH\",\"relationship\":{\"resource\":{\"objectType\":\"space\",\"objectId\":\"${TENANT}_${DEFAULT_SPACE}\"},\"relation\":\"viewer\",\"subject\":{\"object\":{\"objectType\":\"group\",\"objectId\":\"${TENANT}_${MEMBERS_GROUP}\"},\"optionalRelation\":\"member\"}}},
      {\"operation\":\"OPERATION_TOUCH\",\"relationship\":{\"resource\":{\"objectType\":\"group\",\"objectId\":\"${TENANT}_${MEMBERS_GROUP}\"},\"relation\":\"member\",\"subject\":{\"object\":{\"objectType\":\"user\",\"objectId\":\"${UID_}\"}}}}
    ]}" | jq -c '{writtenAt:(.writtenAt.token // .writtenAt // "?")}' 2>/dev/null || echo "   (SpiceDB 写入返回非 JSON，检查连通/schema)"
fi

cat <<EOF

──────── 完成 ────────
  租户(org)      ${TENANT}
  用户           ${USER}  (口令: ${PASSWORD})
  sub(UUID)      ${UID_}
  shared app     ${SHARED_APP}  (base client_id: ${SHARED_CID})
  登录 client_id  ${CID}   (secret 复用 shared)
  edge 白名单     无需改动: CASDOOR_AUDIENCES 已含 base ${SHARED_CID}, 按 <base>-org-* 家族自动放行本租户
  前端           VITE_CASDOOR_CLIENT_ID=${SHARED_CID} + VITE_AUTH_MODE=oidc; 登录页输租户名 ${TENANT} 即可
──────────────────────
EOF
