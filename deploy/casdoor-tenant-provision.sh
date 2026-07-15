#!/usr/bin/env bash
# casdoor-tenant-provision.sh — 造/补 Casdoor 租户身份（org + app + user），可选联动 SpiceDB 成员组。
# -----------------------------------------------------------------------------
# 背景：enforce + Casdoor-only 下，**一个租户 = 一个 Casdoor organization**（edge 把 token 的
#   owner→tenantId、sub→userId）。每个 org 需要自己的 application 承载 password / authorization_code
#   grant；该 app 的 client_id 必须进 edge 的 CASDOOR_AUDIENCES 白名单，否则 token aud 校验 401。
# 本脚本把「建 org → 建 app → 建 user → 设密码 →（可选）写 SpiceDB 成员组」一条龙做掉，幂等可重复跑。
#
# 用法：
#   TENANT=acme USER=bob   PASSWORD='Bob123456'   WIRE_SPICEDB=1 bash deploy/casdoor-tenant-provision.sh
#   TENANT=beta USER=carol PASSWORD='Carol123456' WIRE_SPICEDB=1 bash deploy/casdoor-tenant-provision.sh
#
# 参数(env)：
#   TENANT(必填)   租户 = Casdoor org 名（token owner→tenantId；对象 id 前缀 <tenant>_）
#   USER(必填)     用户名（登录用户名 = token name）
#   PASSWORD(必填) 口令（org 策略默认 AtLeast6）
#   WIRE_SPICEDB   =1 时把该用户 sub(UUID) 加进 SpiceDB group:<tenant>_<MEMBERS_GROUP> 并补
#                  space:<tenant>_<DEFAULT_SPACE>#viewer@group:<tenant>_<MEMBERS_GROUP>#member（D3① 同租户默认可见）
#   MEMBERS_GROUP  成员组名，默认 members
#   DEFAULT_SPACE  默认空间名，默认 default
#   APP_CLIENT_ID / APP_CLIENT_SECRET  该租户 app 的固定凭据（默认按 tenant 派生，纯字母数字）
#   CASDOOR_URL(默认 http://localhost:8000) BUILTIN_CID(默认 ea46d9a8033b0be2d8ed，用于取 admin token)
#   CASDOOR_ADMIN/CASDOOR_ADMIN_PW(默认 admin/123)
#   SPICEDB_HTTP(默认 http://localhost:8543) SPICEDB_KEY(默认 authz_dev_key)
#
# 产出：打印该 app 的 client_id / client_secret 与 user 的 sub(UUID)。
# ⚠️ 若是**新** app（新租户），把打印出的 client_id 追加进 edge CASDOOR_AUDIENCES 后重启 edge：
#      export CASDOOR_AUDIENCES=<旧列表>,<新client_id> EDGE_CASDOOR_ENABLED=true EDGE_CASDOOR_MODE=only ...
#      bash langchain4j-platform/deploy/start-all.sh --no-build --recreate
#    同 org 复用已存在 app 的用户（如再加 acme 用户）无需改 audiences。
# 依赖：curl + jq + docker（查 built-in app 的 client_secret 取 admin token）。注释一律中文。
set -uo pipefail

TENANT="${TENANT:?需要 TENANT（= Casdoor org 名）}"
USER="${USER:?需要 USER}"
PASSWORD="${PASSWORD:?需要 PASSWORD}"
MEMBERS_GROUP="${MEMBERS_GROUP:-members}"
DEFAULT_SPACE="${DEFAULT_SPACE:-default}"
WIRE_SPICEDB="${WIRE_SPICEDB:-0}"
CASDOOR="${CASDOOR_URL:-http://localhost:8000}"
BUILTIN_CID="${BUILTIN_CID:-ea46d9a8033b0be2d8ed}"
ADMIN="${CASDOOR_ADMIN:-admin}"; ADMIN_PW="${CASDOOR_ADMIN_PW:-123}"
SPICEDB_HTTP="${SPICEDB_HTTP:-http://localhost:8543}"
SPICEDB_KEY="${SPICEDB_KEY:-authz_dev_key}"
APP="rag-${TENANT}"
CID="${APP_CLIENT_ID:-rag${TENANT}client01}"
CSEC="${APP_CLIENT_SECRET:-rag${TENANT}secret000000000000000000000000}"

command -v jq >/dev/null || { echo "需要 jq" >&2; exit 1; }

# built-in app 的 secret 不入库，现查 Postgres 取 admin token
BSEC=$(docker exec authz-postgres psql -U authz -d spicedb -tAc \
  "select client_secret from application where client_id='$BUILTIN_CID'" 2>/dev/null | tr -d '[:space:]')
AT=$(curl -s -X POST "$CASDOOR/api/login/oauth/access_token" -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&username=$ADMIN&password=$ADMIN_PW&client_id=$BUILTIN_CID&client_secret=$BSEC&scope=openid" \
  | jq -r '.access_token // empty')
[ -n "$AT" ] || { echo "拿不到 Casdoor admin token（检查 admin/123 与 built-in app 凭据）" >&2; exit 1; }
capi(){ curl -s -X POST "$CASDOOR/api/$1" -H "Authorization: Bearer $AT" -H "Content-Type: application/json" -d "$2"; }

echo "==> 1. org $TENANT"
capi add-organization "{\"owner\":\"admin\",\"name\":\"$TENANT\",\"displayName\":\"$TENANT\",\"passwordType\":\"bcrypt\",\"passwordOptions\":[\"AtLeast6\"],\"defaultAvatar\":\"https://cdn.casbin.org/img/casbin.svg\",\"accountItems\":[{\"name\":\"Password\",\"visible\":true,\"viewRule\":\"Self\",\"modifyRule\":\"Self\"}]}" \
  | jq -c '{status,msg}'

echo "==> 2. app $APP (org=$TENANT, password+authz_code, cert-built-in)"
capi add-application "{\"owner\":\"admin\",\"name\":\"$APP\",\"displayName\":\"$APP\",\"organization\":\"$TENANT\",\"cert\":\"cert-built-in\",\"tokenFormat\":\"JWT\",\"expireInHours\":168,\"refreshExpireInHours\":168,\"enablePassword\":true,\"enableSignUp\":false,\"clientId\":\"$CID\",\"clientSecret\":\"$CSEC\",\"grantTypes\":[\"authorization_code\",\"password\"],\"redirectUris\":[\"http://localhost:8093/callback\"],\"signinMethods\":[{\"name\":\"Password\",\"displayName\":\"Password\",\"rule\":\"All\"}],\"providers\":[]}" \
  | jq -c '{status,msg}'
# 读回真实 client_id/secret（若 app 已存在则以现值为准）
read -r CID CSEC < <(curl -s "$CASDOOR/api/get-application?id=admin/$APP" -H "Authorization: Bearer $AT" \
  | jq -r '.data | .clientId+" "+.clientSecret')

echo "==> 3. user $USER@$TENANT + 设密码"
capi add-user "{\"owner\":\"$TENANT\",\"name\":\"$USER\",\"type\":\"normal-user\",\"displayName\":\"$USER\",\"email\":\"$USER@$TENANT.local\",\"phone\":\"\",\"password\":\"$PASSWORD\",\"signupApplication\":\"$APP\",\"isAdmin\":false,\"isForbidden\":false,\"isDeleted\":false,\"properties\":{}}" \
  | jq -c '{status,msg}'
# set-password 保证按 org bcrypt 落盘（add-user 已带 password 时这步可能报"与当前相同"，无害）
curl -s -X POST "$CASDOOR/api/set-password" -H "Authorization: Bearer $AT" \
  --data-urlencode "userOwner=$TENANT" --data-urlencode "userName=$USER" \
  --data-urlencode "oldPassword=" --data-urlencode "newPassword=$PASSWORD" >/dev/null 2>&1 || true
UID_=$(curl -s "$CASDOOR/api/get-user?id=$TENANT/$USER" -H "Authorization: Bearer $AT" | jq -r '.data.id')
[ -n "$UID_" ] && [ "$UID_" != "null" ] || { echo "读不到 $USER 的 UUID" >&2; exit 1; }

echo "==> 4. 验证 password grant + claims"
TOK=$(curl -s -X POST "$CASDOOR/api/login/oauth/access_token" -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&username=$USER&password=$PASSWORD&client_id=$CID&client_secret=$CSEC&scope=openid profile" \
  | jq -r '.access_token // empty')
if [ -n "$TOK" ]; then
  CLAIMS=$(printf '%s' "$TOK" | cut -d. -f2 | tr '_-' '/+' | { b=$(cat); p=$(( (4-${#b}%4)%4 )); printf '%s%*s' "$b" "$p" '' | tr ' ' '='; } | base64 -d 2>/dev/null | jq -c '{iss,aud,sub,owner,name}')
  echo "   token OK: $CLAIMS"
else
  echo "   ✗ password grant 失败（该用户暂不可登录）" >&2
fi

if [ "$WIRE_SPICEDB" = "1" ]; then
  echo "==> 5. SpiceDB: user:$UID_ → group:${TENANT}_${MEMBERS_GROUP} + space viewer 绑定"
  curl -s -X POST "$SPICEDB_HTTP/v1/relationships/write" -H "Authorization: Bearer $SPICEDB_KEY" -H "Content-Type: application/json" \
    -d "{\"updates\":[
      {\"operation\":\"OPERATION_TOUCH\",\"relationship\":{\"resource\":{\"objectType\":\"space\",\"objectId\":\"${TENANT}_${DEFAULT_SPACE}\"},\"relation\":\"viewer\",\"subject\":{\"object\":{\"objectType\":\"group\",\"objectId\":\"${TENANT}_${MEMBERS_GROUP}\"},\"optionalRelation\":\"member\"}}},
      {\"operation\":\"OPERATION_TOUCH\",\"relationship\":{\"resource\":{\"objectType\":\"group\",\"objectId\":\"${TENANT}_${MEMBERS_GROUP}\"},\"relation\":\"member\",\"subject\":{\"object\":{\"objectType\":\"user\",\"objectId\":\"$UID_\"}}}}
    ]}" | jq -c '{writtenAt:(.writtenAt.token // .writtenAt // "?")}' 2>/dev/null || echo "   (SpiceDB 写入返回非 JSON，检查连通/schema)"
fi

cat <<EOF

──────── 完成 ────────
  租户(org)     $TENANT
  用户          $USER  (口令: $PASSWORD)
  sub(UUID)     $UID_
  app           $APP
  client_id     $CID
  client_secret $CSEC
  edge 白名单    确保 CASDOOR_AUDIENCES 含 ${CID} (新 app 需重启 edge)
──────────────────────
EOF
