#!/usr/bin/env bash
# recsys(搜推广)授权模型 seed + 强一致自校验(schemas/recsys.zed)。
# ⚠️ 目标是 recsys 专属 SpiceDB 实例(§B 每项目独立;dev 默认 :8544,由 recsys 仓库的
#    docker compose 起),不要指到 auth-platform 自己的 :8543(schema/write 整体替换会互删定义)。
# 用法:
#   bash recsys-authz-fixture.sh              # dry-run,只打印将写入的关系
#   APPLY=1 bash recsys-authz-fixture.sh      # 写入 + 强一致断言(失败 exit 3)
# env: SPICEDB_HTTP(默认 http://localhost:8544) SPICEDB_KEY(默认 recsys_dev_key)
#      OWNER1/OPT1/OWNER2/PADMIN/POPS 可覆盖示例主体 id(生产=Casdoor sub UUID)
set -euo pipefail

HTTP="${SPICEDB_HTTP:-http://localhost:8544}"
KEY="${SPICEDB_KEY:-recsys_dev_key}"
SCHEMA="$(dirname "$0")/../auth-platform-core/src/main/resources/schemas/recsys.zed"
AUTH=(-H "Authorization: Bearer ${KEY}" -H "Content-Type: application/json")

OWNER1="${OWNER1:-u_owner1}"   # advertiser:1001 所有人
OPT1="${OPT1:-u_opt1}"         # advertiser:1001 投放成员
OWNER2="${OWNER2:-u_owner2}"   # advertiser:1002 所有人(用于跨广告主隔离断言)
PADMIN="${PADMIN:-u_padmin}"   # 平台管理员
POPS="${POPS:-u_pops}"         # 平台运营(审核/报表)

rel() { # type id relation subjType subjId [subjRelation]
  local base='{"resource":{"objectType":"'$1'","objectId":"'$2'"},"relation":"'$3'","subject":{"object":{"objectType":"'$4'","objectId":"'$5'"}'
  if [ -n "${6:-}" ]; then base+=',"optionalRelation":"'$6'"'; fi
  echo "{\"operation\":\"OPERATION_TOUCH\",\"relationship\":${base}}}}"
}

UPDATES=$(cat <<EOF
[
  $(rel platform recsys admin user "$PADMIN"),
  $(rel platform recsys operator user "$POPS"),
  $(rel advertiser 1001 platform platform recsys),
  $(rel advertiser 1001 owner user "$OWNER1"),
  $(rel advertiser 1001 member user "$OPT1"),
  $(rel advertiser 1002 platform platform recsys),
  $(rel advertiser 1002 owner user "$OWNER2")
]
EOF
)

echo "==> recsys 授权 fixture -> ${HTTP}"
if [ "${APPLY:-0}" != "1" ]; then
  echo "(dry-run,APPLY=1 才写入) 将写 schema recsys.zed + 关系:"
  echo "$UPDATES" | jq -r '.[].relationship | "\(.resource.objectType):\(.resource.objectId)#\(.relation)@\(.subject.object.objectType):\(.subject.object.objectId)"'
  exit 0
fi

echo "==> 1. WriteSchema (recsys.zed)"
curl -sf -X POST "${HTTP}/v1/schema/write" "${AUTH[@]}" \
  -d "$(jq -Rs '{schema: .}' < "${SCHEMA}")" > /dev/null
echo "==> 2. WriteRelationships ($(echo "$UPDATES" | jq 'length') 条)"
curl -sf -X POST "${HTTP}/v1/relationships/write" "${AUTH[@]}" \
  -d "{\"updates\": ${UPDATES}}" > /dev/null

check() { # resType resId perm subjId
  curl -sf -X POST "${HTTP}/v1/permissions/check" "${AUTH[@]}" -d "{
    \"consistency\":{\"fullyConsistent\":true},
    \"resource\":{\"objectType\":\"$1\",\"objectId\":\"$2\"},
    \"permission\":\"$3\",
    \"subject\":{\"object\":{\"objectType\":\"user\",\"objectId\":\"$4\"}}
  }" | jq -r '.permissionship'
}
FAILED=0
assert() { if [ "$2" = "$3" ]; then echo "    PASS  $1"; else echo "    FAIL  $1  got=$2 want=$3"; FAILED=1; fi; }
HAS=PERMISSIONSHIP_HAS_PERMISSION; NO=PERMISSIONSHIP_NO_PERMISSION

echo "==> 3. 强一致自校验"
assert "owner1 可投放操作 1001 (owner→edit)"        "$(check advertiser 1001 edit  "$OWNER1")" "$HAS"
assert "owner1 可账户管理 1001"                     "$(check advertiser 1001 manage "$OWNER1")" "$HAS"
assert "opt1(成员) 可投放操作 1001"                 "$(check advertiser 1001 edit  "$OPT1")"   "$HAS"
assert "opt1(成员) 不可账户管理 1001"               "$(check advertiser 1001 manage "$OPT1")"  "$NO"
assert "owner2 不可见 1001 (跨广告主隔离)"          "$(check advertiser 1001 view  "$OWNER2")" "$NO"
assert "平台管理员可账户管理 1001"                  "$(check advertiser 1001 manage "$PADMIN")" "$HAS"
assert "平台运营可看 1001 报表 (view_reports)"      "$(check advertiser 1001 view  "$POPS")"   "$HAS"
assert "平台运营不可投放操作 1001"                  "$(check advertiser 1001 edit  "$POPS")"   "$NO"
assert "平台运营有创意审核权 (platform.review)"     "$(check platform recsys review "$POPS")"  "$HAS"

echo
if [ "$FAILED" = 0 ]; then echo "✅ recsys 授权模型自校验全绿"; else echo "❌ 有断言失败"; exit 3; fi
