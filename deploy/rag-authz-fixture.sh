#!/usr/bin/env bash
# rag-authz-fixture.sh — 按 backfill-manifest 语义为【一个租户】seed ReBAC 关系。
# 冻结决策 D3=①（同租户默认可见）：把租户成员组绑到 default space 的 viewer。
# 关系形态见 docs/plans/rag-tenant-authz-0715-1005/backfill-manifest-format.md。
#
# 幂等（全 OPERATION_TOUCH，可反复跑）。**默认 dry-run**（只打印将写的元组）；APPLY=1 才真正写入 SpiceDB。
# group id 用 <tenant>_<group>（= CasdoorGroupIds.encode）；资源 id 用 <tenant>_<id>（= KnowledgeResourceIds）。
# 依赖 curl + jq。
#
# 用法:
#   TENANT=acme MEMBERS_GROUP=members MEMBER_USERS="u_alice u_bob" DOC_IDS="a66563a5a4528e01 d_99" \
#     bash deploy/rag-authz-fixture.sh            # dry-run
#   TENANT=acme ... APPLY=1 bash deploy/rag-authz-fixture.sh   # 真正写入
set -euo pipefail

HTTP="${SPICEDB_HTTP:-http://localhost:8543}"
KEY="${SPICEDB_KEY:-authz_dev_key}"
TENANT="${TENANT:?需要 TENANT（=Casdoor org=租户id）}"
MEMBERS_GROUP="${MEMBERS_GROUP:-members}"      # 全体成员组短名（绑 default space viewer 用）
DEFAULT_SPACE="${DEFAULT_SPACE:-default}"
DOC_IDS="${DOC_IDS:-}"                          # 空格分隔 docId（不含租户前缀）
MEMBER_USERS="${MEMBER_USERS:-}"               # 空格分隔 user sub（写入成员组 membership）
OWNER_USER="${OWNER_USER:-}"                   # 可选：给所有 DOC_IDS 写 owner（仅可信来源，勿伪造）
APPLY="${APPLY:-0}"
AUTH=(-H "Authorization: Bearer ${KEY}" -H "Content-Type: application/json")

GROUP_ID="${TENANT}_${MEMBERS_GROUP}"          # = CasdoorGroupIds.encode(TENANT, MEMBERS_GROUP)
SPACE_ID="${TENANT}_${DEFAULT_SPACE}"          # = KnowledgeResourceIds.space(TENANT, DEFAULT_SPACE)

rel() { # type id relation subjType subjId [subjRelation]
  local base='{"resource":{"objectType":"'$1'","objectId":"'$2'"},"relation":"'$3'","subject":{"object":{"objectType":"'$4'","objectId":"'$5'"}'
  if [ -n "${6:-}" ]; then base+=',"optionalRelation":"'$6'"'; fi
  echo "{\"operation\":\"OPERATION_TOUCH\",\"relationship\":${base}}}}"
}

updates=()
# D3=① 同租户默认可见：成员组绑 default space viewer（本租户成员登录即可见本库全部文档）。
updates+=("$(rel space "$SPACE_ID" viewer group "$GROUP_ID" member)")
# 成员组 membership（生产由 GroupSyncService 维护；此处 fixture/引导用）。
for u in $MEMBER_USERS; do
  updates+=("$(rel group "$GROUP_ID" member user "$u")")
done
# 存量文档挂 default space（可由 registry 的 <tenant,docId> 证明）；可选写可信 owner。
for d in $DOC_IDS; do
  updates+=("$(rel document "${TENANT}_${d}" parent_space space "$SPACE_ID")")
  if [ -n "$OWNER_USER" ]; then
    updates+=("$(rel document "${TENANT}_${d}" owner user "$OWNER_USER")")
  fi
done

JSON=$(printf '%s,' "${updates[@]}"); JSON="[${JSON%,}]"
echo "tenant=$TENANT  group=$GROUP_ID  space=$SPACE_ID  docs=[$DOC_IDS]  members=[$MEMBER_USERS]"
if [ "$APPLY" = "1" ]; then
  curl -sf -X POST "$HTTP/v1/relationships/write" "${AUTH[@]}" -d "{\"updates\": $JSON}" > /dev/null
  echo "APPLIED $(echo "$JSON" | jq 'length') relationships to SpiceDB($HTTP)."

  # --- F2 门禁：APPLY 后强一致校验 D3 链确已生效（成员 → group#member → space viewer → document parent_space->view）。
  # "写了关系" ≠ "判权生效"：schema/箭头缺失、group 写错都会让写成功但 view 推不出来。此处实际 check，失败非零退出，
  # 杜绝把"其实所有非 owner 都会被误拒"的库切进 enforce。需至少一个 MEMBER_USERS 与一个 DOC_IDS 才能校验。
  # 取一个【非 OWNER_USER】的成员做 view 校验，隔离 owner/直授路径——否则 owner==member 时即使 D3 链断裂，
  # 也会因 owner→edit→view 假阳性通过，门禁形同虚设。已知局限：存量文档若已对该成员有直授 viewer/editor，
  # seed 脚本无法排除，仍可能假阳性（见 FINAL_PLAN F2 门禁说明）。
  V_USER=""
  for u in $MEMBER_USERS; do
    if [ "$u" != "$OWNER_USER" ]; then V_USER="$u"; break; fi
  done
  set -- $DOC_IDS; V_DOC="${1:-}"
  if [ -n "$V_USER" ] && [ -n "$V_DOC" ]; then
    CHECK='{"consistency":{"fullyConsistent":true},"resource":{"objectType":"document","objectId":"'"${TENANT}_${V_DOC}"'"},"permission":"view","subject":{"object":{"objectType":"user","objectId":"'"${V_USER}"'"}}}'
    SHIP=$(curl -sf -X POST "$HTTP/v1/permissions/check" "${AUTH[@]}" -d "$CHECK" | jq -r '.permissionship // empty')
    if [ "$SHIP" != "PERMISSIONSHIP_HAS_PERMISSION" ]; then
      echo "VERIFY FAIL: 非 owner 成员 $V_USER 对 document:${TENANT}_${V_DOC} 的 view 未生效 (permissionship=${SHIP:-无响应})" >&2
      echo "  D3 链未成立：请确认已写入 space:${SPACE_ID}#viewer@group:${GROUP_ID}#member、group:${GROUP_ID}#member@user:${V_USER}、document:${TENANT}_${V_DOC}#parent_space@space:${SPACE_ID}。" >&2
      exit 3
    fi
    echo "VERIFY OK: 非 owner 成员 $V_USER 经 D3 链对 document:${TENANT}_${V_DOC} 有 view —— 可安全用于 enforce。"
  else
    # 无法隔离验证 D3（没有非 owner 成员，或没有 DOC_IDS）。APPLY 下这是"未验证的库"，默认非零退出，杜绝绕过
    # 门禁得到绿色发布；分步 seeding（如只写 membership）可显式 ALLOW_UNVERIFIED=1 放行。
    REASON="需至少一个【非 OWNER_USER】的成员与一个 DOC_IDS（当前 non-owner member='${V_USER}', DOC_IDS='${DOC_IDS}'）"
    if [ "${ALLOW_UNVERIFIED:-0}" = "1" ]; then
      echo "VERIFY SKIP(ALLOW_UNVERIFIED=1): 无法隔离验证 D3——${REASON}。分步 seeding 显式放行。" >&2
    else
      echo "VERIFY FAIL: 无法隔离验证 D3——${REASON}。拒绝把未验证的库用于 enforce（分步 seeding 请设 ALLOW_UNVERIFIED=1）。" >&2
      exit 4
    fi
  fi
else
  echo "DRY-RUN（设 APPLY=1 才写入）— 将写入 $(echo "$JSON" | jq 'length') 条:"
  echo "$JSON" | jq .
fi
