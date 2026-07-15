#!/usr/bin/env bash
# dept-authz-fixture.sh — seed【部门层级授权模型】(取代 D3) 到 SpiceDB，用于 dev/demo/测试。
# 需先把含 department 定义的 knowledge.zed 写入该 SpiceDB。
#
# 部门树:  <t>_rnd(研发与数据中心) → <t>_platform(技术平台部) → <t>_ecom(电商组);  平级 <t>_product(产品部)
# 成员:    alice@ecom, bob@platform, carol@rnd, dave@product;  padmin = platform 管理员(V-03)
# 文档:    <t>_doc1  home_dept=ecom  owner=alice
#
# 默认 dry-run；APPLY=1 才写入。APPLY 后校验模型(本部门+上级可读、平级不可读、edit 仅 owner、share 含祖先管理员)。
# 依赖 curl + jq。对象 id 全带 <TENANT>_ 前缀(项目内租户维度)。
set -euo pipefail

HTTP="${SPICEDB_HTTP:-http://localhost:8543}"
KEY="${SPICEDB_KEY:-authz_dev_key}"
T="${TENANT:-demo}"
APPLY="${APPLY:-0}"
AUTH=(-H "Authorization: Bearer ${KEY}" -H "Content-Type: application/json")

id() { echo "${T}_$1"; }
rel() { # rtype rid relation stype sid
  echo "{\"operation\":\"OPERATION_TOUCH\",\"relationship\":{\"resource\":{\"objectType\":\"$1\",\"objectId\":\"$2\"},\"relation\":\"$3\",\"subject\":{\"object\":{\"objectType\":\"$4\",\"objectId\":\"$5\"}}}}"
}

U=()
# 部门树 parent 边
U+=("$(rel department "$(id platform)" parent department "$(id rnd)")")
U+=("$(rel department "$(id ecom)"     parent department "$(id platform)")")
# 成员(一人一部门)
U+=("$(rel department "$(id rnd)"      member user "$(id carol)")")
U+=("$(rel department "$(id platform)" member user "$(id bob)")")
U+=("$(rel department "$(id ecom)"     member user "$(id alice)")")
U+=("$(rel department "$(id product)"  member user "$(id dave)")")
# 部门管理员(V-03: platform 的 padmin)
U+=("$(rel department "$(id platform)" admin  user "$(id padmin)")")
# 文档: 归属电商组，owner=alice
U+=("$(rel document "$(id doc1)" home_dept department "$(id ecom)")")
U+=("$(rel document "$(id doc1)" owner    user       "$(id alice)")")

JSON=$(printf '%s,' "${U[@]}"); JSON="[${JSON%,}]"
echo "tenant=$T  部门树=[rnd → platform → ecom, product(平级)]  文档=$(id doc1)@$(id ecom) owner=$(id alice)"

if [ "$APPLY" != "1" ]; then
  echo "DRY-RUN(设 APPLY=1 才写入)— 将写 $(echo "$JSON" | jq 'length') 条:"
  echo "$JSON" | jq -c '.[].relationship | "\(.resource.objectType):\(.resource.objectId) #\(.relation)@ \(.subject.object.objectType):\(.subject.object.objectId)"'
  exit 0
fi

curl -sf -X POST "$HTTP/v1/relationships/write" "${AUTH[@]}" -d "{\"updates\": $JSON}" > /dev/null
echo "APPLIED $(echo "$JSON" | jq 'length') relationships to SpiceDB($HTTP)."

# --- 模型校验(强一致)：证明部门隔离生效，失败非零退出 ---
chk() { # permission userShort
  curl -sf -X POST "$HTTP/v1/permissions/check" "${AUTH[@]}" \
    -d "{\"consistency\":{\"fullyConsistent\":true},\"resource\":{\"objectType\":\"document\",\"objectId\":\"$(id doc1)\"},\"permission\":\"$1\",\"subject\":{\"object\":{\"objectType\":\"user\",\"objectId\":\"$(id "$2")\"}}}" \
    | jq -r '.permissionship // "ERR"'
}
FAIL=0
assert() { # permission userShort expectYES/NO
  local got ok=NO; got=$(chk "$1" "$2"); [ "$got" = "PERMISSIONSHIP_HAS_PERMISSION" ] && ok=YES
  if [ "$ok" = "$3" ]; then echo "  ✅ $1 $2 → $ok"; else echo "  ❌ $1 $2 → $ok (期望 $3) [$got]"; FAIL=1; fi
}
echo "== 模型校验 =="
assert view  alice  YES   # 本部门(owner)
assert view  bob    YES   # 上级(技术平台部)
assert view  carol  YES   # 上级(研发中心)
assert view  dave   NO    # 平级(产品部)读不到
assert share alice  YES   # owner 可分享
assert share padmin YES   # 上级部门管理员可分享
assert share bob    NO    # 上级普通成员不可分享
assert edit  alice  YES   # 删除/覆盖仅 owner
assert edit  bob    NO
[ "$FAIL" = 0 ] && echo "✅ 部门层级模型校验通过" || { echo "❌ 校验失败"; exit 3; }
