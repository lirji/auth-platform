#!/usr/bin/env bash
# Phase 0 SpiceDB 冒烟: 灌 knowledge.zed schema + seed 代表性关系 + 判定断言。
# 覆盖两类继承: space/folder 向下继承(组→角色→资源) + document 部门层级模型(向上传播,当前模型)。
# 依赖: curl, jq。用 SpiceDB HTTP 网关(默认 :8543)。
set -euo pipefail

HTTP="${SPICEDB_HTTP:-http://localhost:8543}"
KEY="${SPICEDB_KEY:-authz_dev_key}"
SCHEMA_DIR="$(dirname "$0")/../auth-platform-core/src/main/resources/schemas"
AUTH=(-H "Authorization: Bearer ${KEY}" -H "Content-Type: application/json")

# schema/write 是整体替换语义: 必须写合并后的全量 schema(knowledge+his),
# 只写 knowledge.zed 会试图删掉 his 的 dept/patient/encounter —— 有存量元组时被 SpiceDB 拒绝。
echo "==> 1. WriteSchema (knowledge.zed + his.zed 合并)"
cat "${SCHEMA_DIR}/knowledge.zed" "${SCHEMA_DIR}/his.zed" | jq -Rs '{schema: .}' \
  | curl -sf -X POST "${HTTP}/v1/schema/write" "${AUTH[@]}" -d @- > /dev/null
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
  $(rel folder f_papers parent_space space kb_ml),

  $(rel department eng_child parent department eng_root),
  $(rel department eng_sib parent department eng_root),
  $(rel department eng_child member user u_dana),
  $(rel department eng_root member user u_boss),
  $(rel department eng_sib member user u_sib),
  $(rel document d_42 home_dept department eng_child),
  $(rel document d_42 owner user u_dana),
  $(rel document d_99 viewer user u_carol),
  $(rel document d_77 public user '*')
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
echo "==> 3. Checks: space/folder 向下继承 (组→角色→资源)"
assert "bob 能编辑 f_papers (研发组->space editor->folder 继承)" "$(check folder f_papers edit u_bob)" "PERMISSIONSHIP_HAS_PERMISSION"
assert "carol 不能编辑 f_papers"                                "$(check folder f_papers edit u_carol)" "PERMISSIONSHIP_NO_PERMISSION"

echo "==> 4. Checks: document 部门层级模型 (本部门+上级可读,平级不可,edit 仅 owner)"
assert "dana(owner/本部门) 能看 d_42"            "$(check document d_42 view u_dana)" "PERMISSIONSHIP_HAS_PERMISSION"
assert "boss(上级部门成员) 能看 d_42 (向上传播)" "$(check document d_42 view u_boss)" "PERMISSIONSHIP_HAS_PERMISSION"
assert "sib(平级部门) 不能看 d_42"               "$(check document d_42 view u_sib)"  "PERMISSIONSHIP_NO_PERMISSION"
assert "boss 不能编辑 d_42 (edit 仅 owner)"      "$(check document d_42 edit u_boss)" "PERMISSIONSHIP_NO_PERMISSION"
assert "dana(本部门成员) 能分享 d_42"            "$(check document d_42 share u_dana)" "PERMISSIONSHIP_HAS_PERMISSION"
assert "carol 能看 d_99 (单篇直授 viewer)"       "$(check document d_99 view u_carol)" "PERMISSIONSHIP_HAS_PERMISSION"
# 公开文档(public@user:*): 任意陌生用户 u_zzz 能看公开的 d_77, 但看不到私有的 d_99
assert "陌生人 u_zzz 能看 d_77 (public)"         "$(check document d_77 view u_zzz)" "PERMISSIONSHIP_HAS_PERMISSION"
assert "陌生人 u_zzz 不能看 d_99 (私有)"         "$(check document d_99 view u_zzz)" "PERMISSIONSHIP_NO_PERMISSION"

echo "==> 5. LookupResources: carol 能看哪些 document (须含 d_99 直授 + d_77 公开)"
# 包含式断言(而非精确集合): dev 实例可能有其它 fixture 的残留元组, 不影响本脚本验证的模型语义。
LR=$(curl -sf -X POST "${HTTP}/v1/permissions/resources" "${AUTH[@]}" -d '{
  "consistency":{"fullyConsistent":true},
  "resourceObjectType":"document","permission":"view",
  "subject":{"object":{"objectType":"user","objectId":"u_carol"}}
}' | jq -r 'select(.result!=null) | .result.resourceObjectId' | sort | paste -sd, -)
lr_has() { case ",${LR}," in *",$1,"*) echo yes;; *) echo no;; esac; }
assert "lookupResources 含 d_99 (直授)" "$(lr_has d_99)" "yes"
assert "lookupResources 含 d_77 (公开)" "$(lr_has d_77)" "yes"

echo
if [ "$FAILED" = 0 ]; then echo "✅ SpiceDB 冒烟全部通过 (schema + 向下继承 + 部门层级 + 公开 + lookup)"; else echo "❌ 有断言失败"; exit 1; fi
