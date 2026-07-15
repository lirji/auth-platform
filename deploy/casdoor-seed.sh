#!/usr/bin/env bash
# casdoor-seed.sh — 把 langchain4j auth-service 的 SeedRoles「角色→scope」初始化进 Casdoor。
#
# 建模：每个业务 scope 一个 Casdoor permission（name=scope），permission.roles = 拥有该 scope 的角色。
# 于是 user→role→permission 的展开由 Casdoor 完成，token.permissions 直接带用户的 scope（对象含 name）。
# edge 只从 token.permissions[].name 提取 + allowlist 过滤，**不维护 role→scope 表**（见 ③ 设计 D3）。
#
# ⚠️ 重要顺序：Casdoor 的 permission→role→user 展开在 permission【写入时】确定（role.users 后变不会
# 重算已存在的 permission）。故必须【先给用户分配角色(role.users)】再跑本脚本——脚本重建 permission，
# 基于当前 role.users 展开。若先跑脚本再分配用户，需再跑一次让 permission 重建。
# 幂等：角色不存在才建(保留其 users 分配)；permission 每次 delete+add 重建。可重复跑。需 curl + jq。
# 用法：
#   CASDOOR_CLIENT_ID=... CASDOOR_CLIENT_SECRET=... bash deploy/casdoor-seed.sh
# 可选：CASDOOR_URL(默认 http://localhost:8000) CASDOOR_ORG(默认 built-in)
#       CASDOOR_ADMIN/CASDOOR_ADMIN_PW(默认 admin/123) CASDOOR_MODEL(默认 <org>/user-model-built-in)
set -euo pipefail

CASDOOR="${CASDOOR_URL:-http://localhost:8000}"
ORG="${CASDOOR_ORG:-built-in}"
CID="${CASDOOR_CLIENT_ID:?需要 CASDOOR_CLIENT_ID}"
CSEC="${CASDOOR_CLIENT_SECRET:?需要 CASDOOR_CLIENT_SECRET}"
MODEL="${CASDOOR_MODEL:-$ORG/user-model-built-in}"
ADMIN="${CASDOOR_ADMIN:-admin}"
ADMIN_PW="${CASDOOR_ADMIN_PW:-123}"

AT=$(curl -s -X POST "$CASDOOR/api/login/oauth/access_token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&username=$ADMIN&password=$ADMIN_PW&client_id=$CID&client_secret=$CSEC&scope=openid" \
  | jq -r .access_token)
[ -n "$AT" ] && [ "$AT" != "null" ] || { echo "拿不到 Casdoor admin token，检查凭据" >&2; exit 1; }

# --fail-with-body: HTTP 4xx/5xx 时 curl 返回非 0（不再把失败当成功）。角色 add 已存在的 4xx 由调用处 || true 吞掉。
post() { curl -s --fail-with-body -X POST "$CASDOOR/api/$1" -H "Authorization: Bearer $AT" -H "Content-Type: application/json" -d "$2"; }

# ---- 5 角色（对应 SeedRoles）：不存在才建，保留已有角色的 users 分配（不覆盖）----
# 用户→角色分配由 Casdoor 管（注册/迁移时）；本脚本不碰 role.users。
for r in viewer editor analyst approver admin; do
  post add-role "{\"owner\":\"$ORG\",\"name\":\"$r\",\"displayName\":\"$r\",\"isEnabled\":true}" >/dev/null 2>&1 || true
done
echo "roles: viewer editor analyst approver admin"

# ---- 11 scope permission，roles=拥有该 scope 的角色（SeedRoles 反转）----
perm() { # $1=scope  $2=角色 csv
  local roles_json
  roles_json=$(printf '%s' "$2" | tr ',' '\n' | sed "s#.*#\"$ORG/&\"#" | paste -sd, -)
  post delete-permission "{\"owner\":\"$ORG\",\"name\":\"$1\"}" >/dev/null 2>&1 || true
  post add-permission "{\"owner\":\"$ORG\",\"name\":\"$1\",\"displayName\":\"$1\",\"model\":\"$MODEL\",\"resourceType\":\"Custom\",\"resources\":[\"scope\"],\"actions\":[\"Read\"],\"effect\":\"Allow\",\"roles\":[$roles_json],\"isEnabled\":true}" >/dev/null
  echo "  perm $1 -> [$2]"
}
perm chat          viewer,editor,analyst,approver,admin
perm ingest        editor,admin
perm analytics     analyst,admin
perm approve       approver,admin
perm agent         admin
perm channel       admin
perm eval          admin
perm vision        admin
perm voice         admin
perm role-admin    admin
perm public-ingest admin

echo "done: 5 roles + 11 scope-permissions seeded into Casdoor org=$ORG"
echo "接下来：给用户分配角色（Casdoor 后台 / update-user groups+roles），token.permissions 即带对应 scope。"
