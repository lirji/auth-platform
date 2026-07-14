#!/usr/bin/env bash
# Phase 1 server 冒烟: 经 auth-platform-server(:8200) REST 复验 AuthzEngine 全链路。
# 用独立命名(smk_*)对象 id 与其它数据隔离, 结束清理。
# 读断言一律 full 一致性(避免刚写入被量化快照漏读——写后读须带 ZedToken 或 full)。
# 前置: SpiceDB 已加载 knowledge.zed (先跑 spicedb-smoke.sh); server 已启动 (:8200)。
set -uo pipefail
S="${AUTHZ_SERVER:-http://localhost:8200}"
J() { curl -sf -X POST "$S$1" -H 'Content-Type: application/json' -d "$2"; }
FAILED=0
assert() { if [ "$2" = "$3" ]; then echo "  PASS  $1 -> $2"; else echo "  FAIL  $1 got=[$2] want=[$3]"; FAILED=1; fi; }
# full 一致性判权
chk() { J /v1/check "{\"subject\":{\"type\":\"user\",\"id\":\"$1\"},\"permission\":\"$2\",\"resource\":{\"type\":\"document\",\"id\":\"$3\"},\"consistency\":{\"mode\":\"full\"}}" | jq -r .allowed; }

echo "==> seed (smk_* 独立命名, 经 server 写)"
J /v1/relationships '{"updates":[
  {"operation":"TOUCH","resource":{"type":"space","id":"smk_space"},"relation":"editor","subject":{"type":"group","id":"smk_grp","relation":"member"}},
  {"operation":"TOUCH","resource":{"type":"group","id":"smk_grp"},"relation":"member","subject":{"type":"user","id":"smk_bob","relation":null}},
  {"operation":"TOUCH","resource":{"type":"document","id":"smk_da"},"relation":"parent_space","subject":{"type":"space","id":"smk_space","relation":null}},
  {"operation":"TOUCH","resource":{"type":"document","id":"smk_db"},"relation":"viewer","subject":{"type":"user","id":"smk_carol","relation":null}}
]}' >/dev/null && echo "  ok"

echo "==> check / check-bulk / lookup (full 一致性)"
assert "bob view smk_da (组->space editor 继承)" "$(chk smk_bob view smk_da)" "true"
assert "carol view smk_da"                       "$(chk smk_carol view smk_da)" "false"
assert "carol view smk_db (直授)"                "$(chk smk_carol view smk_db)" "true"
assert "check-bulk carol [smk_da,smk_db]" "$(J /v1/check-bulk '{"subject":{"type":"user","id":"smk_carol"},"permission":"view","resources":[{"type":"document","id":"smk_da"},{"type":"document","id":"smk_db"}],"consistency":{"mode":"full"}}' | jq -c '[.results[].allowed]')" "[false,true]"
assert "lookup-subjects smk_space edit (只 bob)" "$(J /v1/lookup-subjects '{"resource":{"type":"space","id":"smk_space"},"permission":"edit","subjectType":"user","consistency":{"mode":"full"}}' | jq -r '[.subjects[].id]|sort|join(",")')" "smk_bob"

echo "==> 写+AT_LEAST_AS_FRESH 立即生效 + 撤销"
assert "dave view smk_da 授权前" "$(chk smk_dave view smk_da)" "false"
TOK=$(J /v1/relationships '{"updates":[{"operation":"TOUCH","resource":{"type":"document","id":"smk_da"},"relation":"viewer","subject":{"type":"user","id":"smk_dave","relation":null}}]}' | jq -r .token)
FRESH=$(J /v1/check "{\"subject\":{\"type\":\"user\",\"id\":\"smk_dave\"},\"permission\":\"view\",\"resource\":{\"type\":\"document\",\"id\":\"smk_da\"},\"consistency\":{\"mode\":\"at_least_as_fresh\",\"zedToken\":\"$TOK\"}}" | jq -r .allowed)
assert "dave view smk_da 授权后(fresh 立即生效)" "$FRESH" "true"
J /v1/relationships/delete '{"filter":{"resourceType":"document","resourceId":"smk_da","relation":"viewer"}}' >/dev/null
assert "dave view smk_da 撤销后(full)" "$(chk smk_dave view smk_da)" "false"

echo "==> 清理 smk_* 关系"
for id in smk_da smk_db; do J /v1/relationships/delete "{\"filter\":{\"resourceType\":\"document\",\"resourceId\":\"$id\"}}" >/dev/null; done
J /v1/relationships/delete '{"filter":{"resourceType":"space","resourceId":"smk_space"}}' >/dev/null
J /v1/relationships/delete '{"filter":{"resourceType":"group","resourceId":"smk_grp"}}' >/dev/null
echo "  cleaned"

echo
if [ "$FAILED" = 0 ]; then echo "✅ server(:8200) 冒烟全部通过"; else echo "❌ 有断言失败"; exit 1; fi
