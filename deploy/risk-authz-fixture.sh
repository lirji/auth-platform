#!/usr/bin/env bash
# risk-platform 案件归属模型 seed + 强一致自校验（schemas/risk.zed）。
# ⚠️ 目标必须是 risk 专属 SpiceDB（dev 默认 :8545），不能指向 auth-platform :8543；
#    schema/write 是整体替换语义，写错实例会破坏其他项目 schema。
#
# 用法：
#   bash deploy/risk-authz-fixture.sh              # dry-run
#   APPLY=1 bash deploy/risk-authz-fixture.sh      # 写 schema/关系并执行 allow/deny 断言
# env：SPICEDB_HTTP（默认 http://localhost:8545） SPICEDB_KEY（默认 risk_dev_key）
#      ASSIGNEE/OTHER/CASE_ID 可覆盖 fixture 主体和案件 id。
set -euo pipefail

HTTP="${SPICEDB_HTTP:-http://localhost:8545}"
KEY="${SPICEDB_KEY:-risk_dev_key}"
SCHEMA="$(dirname "$0")/../auth-platform-core/src/main/resources/schemas/risk.zed"
AUTH=(-H "Authorization: Bearer ${KEY}" -H "Content-Type: application/json")

ASSIGNEE="${ASSIGNEE:-risk_fixture_assignee}"
OTHER="${OTHER:-risk_fixture_other}"
CASE_ID="${CASE_ID:-risk-platform_fixture-case-1}"

UPDATES="$(jq -nc \
  --arg assignee "${ASSIGNEE}" \
  --arg caseId "${CASE_ID}" \
  '[{operation:"OPERATION_TOUCH",relationship:{resource:{objectType:"risk_case",objectId:$caseId},relation:"assignee",subject:{object:{objectType:"user",objectId:$assignee}}}}]')"

echo "==> risk 授权 fixture -> ${HTTP}"
if [ "${APPLY:-0}" != "1" ]; then
  echo "(dry-run，APPLY=1 才写入) 将写 risk.zed 与关系："
  echo "${UPDATES}" | jq -r '.[] | .relationship | "\(.resource.objectType):\(.resource.objectId)#\(.relation)@\(.subject.object.objectType):\(.subject.object.objectId)"'
  exit 0
fi

echo "==> 1. WriteSchema (risk.zed)"
curl -sf -X POST "${HTTP}/v1/schema/write" "${AUTH[@]}" \
  -d "$(jq -Rs '{schema:.}' < "${SCHEMA}")" >/dev/null

echo "==> 2. WriteRelationships"
curl -sf -X POST "${HTTP}/v1/relationships/write" "${AUTH[@]}" \
  -d "$(jq -nc --argjson updates "${UPDATES}" '{updates:$updates}')" >/dev/null

check() {
  local subject="$1"
  curl -sf -X POST "${HTTP}/v1/permissions/check" "${AUTH[@]}" -d "$(jq -nc \
    --arg caseId "${CASE_ID}" --arg subject "${subject}" \
    '{consistency:{fullyConsistent:true},resource:{objectType:"risk_case",objectId:$caseId},permission:"work",subject:{object:{objectType:"user",objectId:$subject}}}')" \
    | jq -r '.permissionship'
}

FAILED=0
assert() {
  if [ "$2" = "$3" ]; then
    echo "    PASS  $1"
  else
    echo "    FAIL  $1  got=$2 want=$3"
    FAILED=1
  fi
}

echo "==> 3. 强一致自校验"
assert "assignee 可处理案件" "$(check "${ASSIGNEE}")" "PERMISSIONSHIP_HAS_PERMISSION"
assert "other 不可处理案件" "$(check "${OTHER}")" "PERMISSIONSHIP_NO_PERMISSION"

if [ "${FAILED}" = 0 ]; then
  echo "✅ risk 授权模型自校验全绿"
else
  echo "❌ risk 授权模型存在失败断言"
  exit 3
fi
