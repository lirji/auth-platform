#!/usr/bin/env bash
# Phase 0 SpiceDB 冒烟: 灌 knowledge.zed schema + seed 代表性关系 + 跑三条判定断言。
# 依赖: curl, jq。用 SpiceDB HTTP 网关(默认 :8543)。
set -euo pipefail

HTTP="${SPICEDB_HTTP:-http://localhost:8543}"
KEY="${SPICEDB_KEY:-authz_dev_key}"
SCHEMA="${SCHEMA_FILE:-$(dirname "$0")/../auth-platform-core/src/main/resources/schemas/knowledge.zed}"
AUTH=(-H "Authorization: Bearer ${KEY}" -H "Content-Type: application/json")

echo "==> 1. WriteSchema (${SCHEMA})"
curl -sf -X POST "${HTTP}/v1/schema/write" "${AUTH[@]}" \
  -d "$(jq -Rs '{schema: .}' < "${SCHEMA}")" > /dev/null
echo "    schema written."

rel() { # type id relation subjType subjId [subjRelation]
  local base='{"resource":{"objectType":"'$1'","objectId":"'$2'"},"relation":"'$3'","subject":{"object":{"objectType":"'$4'","objectId":"'$5'"}'
  if [ -n "${6:-}" ]; then base+=',"optionalRelation":"'$6'"'; fi
  echo "{\"operation\":\"OPERATION_TOUCH\",\"relationship\":${base}}}}"
}

echo "==> 2. WriteRelationships (seed)"
UPDATES=$(cat <<EOF
[
  $(rel space kb_ml owner user u_alice),
  $(rel space kb_ml editor group research member),
  $(rel group research member user u_bob),
  $(rel space kb_ml parent_org organization acme),
  $(rel organization acme admin group it_admins member),
  $(rel document d_42 parent_space space kb_ml),
  $(rel document d_42 parent_folder folder f_papers),
  $(rel folder f_papers parent_space space kb_ml),
  $(rel document d_99 viewer user u_carol),
  $(rel document d_77 public_viewer user '*')
]
EOF
)
curl -sf -X POST "${HTTP}/v1/relationships/write" "${AUTH[@]}" \
  -d "{\"updates\": ${UPDATES}}" > /dev/null
echo "    $(echo "$UPDATES" | jq 'length') relationships written."

check() { # resType resId perm subjId  -> prints permissionship
  curl -sf -X POST "${HTTP}/v1/permissions/check" "${AUTH[@]}" -d "{
    \"consistency\":{\"fullyConsistent\":true},
    \"resource\":{\"objectType\":\"$1\",\"objectId\":\"$2\"},
    \"permission\":\"$3\",
    \"subject\":{\"object\":{\"objectType\":\"user\",\"objectId\":\"$4\"}}
  }" | jq -r '.permissionship'
}

assert() { # label actual expected
  if [ "$2" = "$3" ]; then echo "    PASS  $1  ($2)"; else echo "    FAIL  $1  got=$2 want=$3"; FAILED=1; fi
}

FAILED=0
echo "==> 3. Checks"
assert "bob 能看 d_42 (研发组->space editor->继承)" "$(check document d_42 view u_bob)" "PERMISSIONSHIP_HAS_PERMISSION"
assert "carol 不能看 d_42"                          "$(check document d_42 view u_carol)" "PERMISSIONSHIP_NO_PERMISSION"
assert "carol 能看 d_99 (单篇直授)"                 "$(check document d_99 view u_carol)" "PERMISSIONSHIP_HAS_PERMISSION"
# 公开性(public_viewer@user:*): 任意陌生用户 u_zzz 能看公开的 d_77, 但看不到私有的 d_99
assert "陌生人 u_zzz 能看 d_77 (公开)"              "$(check document d_77 view u_zzz)" "PERMISSIONSHIP_HAS_PERMISSION"
assert "陌生人 u_zzz 不能看 d_99 (私有)"            "$(check document d_99 view u_zzz)" "PERMISSIONSHIP_NO_PERMISSION"

echo "==> 4. LookupResources: carol 能看哪些 document (期望 d_99 私有 + d_77 公开)"
LR=$(curl -sf -X POST "${HTTP}/v1/permissions/resources" "${AUTH[@]}" -d '{
  "consistency":{"fullyConsistent":true},
  "resourceObjectType":"document","permission":"view",
  "subject":{"object":{"objectType":"user","objectId":"u_carol"}}
}' | jq -r 'select(.result!=null) | .result.resourceObjectId' | sort | paste -sd, -)
assert "lookupResources(carol, view, document)" "${LR}" "d_77,d_99"

echo
if [ "$FAILED" = 0 ]; then echo "✅ SpiceDB 冒烟全部通过 (schema + 继承 + 单篇授权 + lookup)"; else echo "❌ 有断言失败"; exit 1; fi
