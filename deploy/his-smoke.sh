#!/usr/bin/env bash
# Phase 4 HIS 数据权限冒烟: 灌 合并schema(knowledge+his) + seed HIS 关系 + 跑"本科室/科主任/主治跨科/作者"判定。
# 依赖 curl, jq。用 SpiceDB HTTP 网关(默认 :8543)。
set -euo pipefail
HTTP="${SPICEDB_HTTP:-http://localhost:8543}"
KEY="${SPICEDB_KEY:-authz_dev_key}"
DIR="$(dirname "$0")/../auth-platform-core/src/main/resources/schemas"
AUTH=(-H "Authorization: Bearer ${KEY}" -H "Content-Type: application/json")

echo "==> 1. WriteSchema (knowledge.zed + his.zed 合并)"
COMBINED="$(cat "${DIR}/knowledge.zed" "${DIR}/his.zed")"
curl -sf -X POST "${HTTP}/v1/schema/write" "${AUTH[@]}" \
  -d "$(jq -Rs '{schema: .}' <<< "$COMBINED")" > /dev/null && echo "    合并 schema 已写入(knowledge + his 定义共存)"

rel() { echo "{\"operation\":\"OPERATION_TOUCH\",\"relationship\":{\"resource\":{\"objectType\":\"$1\",\"objectId\":\"$2\"},\"relation\":\"$3\",\"subject\":{\"object\":{\"objectType\":\"$4\",\"objectId\":\"$5\"}}}}"; }

echo "==> 2. WriteRelationships (HIS seed)"
UPD=$(cat <<EOF
[
  $(rel dept 101 member user dr_a),
  $(rel dept 102 member user dr_b),
  $(rel dept 101 head user dr_chief),
  $(rel dept 102 member user dr_x),
  $(rel encounter E1 dept dept 101),
  $(rel encounter E1 author user dr_a),
  $(rel patient P1 attending user dr_x),
  $(rel encounter E2 dept dept 101),
  $(rel encounter E2 subject patient P1)
]
EOF
)
curl -sf -X POST "${HTTP}/v1/relationships/write" "${AUTH[@]}" -d "{\"updates\": ${UPD}}" > /dev/null
echo "    $(jq 'length' <<< "$UPD") 条关系写入"

chk() { # subj perm enc
  curl -sf -X POST "${HTTP}/v1/permissions/check" "${AUTH[@]}" -d "{\"consistency\":{\"fullyConsistent\":true},\"resource\":{\"objectType\":\"encounter\",\"objectId\":\"$3\"},\"permission\":\"$2\",\"subject\":{\"object\":{\"objectType\":\"user\",\"objectId\":\"$1\"}}}" | jq -r '.permissionship'; }
FAILED=0
assert() { [ "$2" = "$3" ] && echo "    PASS  $1" || { echo "    FAIL  $1 got=$2"; FAILED=1; }; }
Y=PERMISSIONSHIP_HAS_PERMISSION; N=PERMISSIONSHIP_NO_PERMISSION

echo "==> 3. 数据权限判定"
assert "dr_a 看 E1 (本科室101 + 作者)"          "$(chk dr_a view E1)" "$Y"
assert "dr_b 看 E1 (他科102, 无关系)→拒"         "$(chk dr_b view E1)" "$N"
assert "dr_chief 看 E1 (101科主任→全科)"          "$(chk dr_chief view E1)" "$Y"
assert "dr_x 看 E2 (主治P1, 跨科102→101)"         "$(chk dr_x view E2)" "$Y"
assert "dr_b 看 E2 (无关系)→拒"                   "$(chk dr_b view E2)" "$N"
assert "dr_a 改 E1 (作者)"                        "$(chk dr_a edit E1)" "$Y"
assert "dr_a 改 E2 (非作者/非主任)→拒"            "$(chk dr_a edit E2)" "$N"
assert "dr_chief 改 E2 (101科主任)"               "$(chk dr_chief edit E2)" "$Y"

echo
[ "$FAILED" = 0 ] && echo "✅ HIS 数据权限模型冒烟全部通过(本科室/科主任/主治跨科/作者)" || { echo "❌ 有断言失败"; exit 1; }
