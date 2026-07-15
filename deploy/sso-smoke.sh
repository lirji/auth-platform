#!/usr/bin/env bash
# 统一登录(Casdoor SSO)接入冒烟: 复现《docs/统一登录平台接入手册.md》§9 Layer 0-2 的断言。
# 依赖: curl, jq。用于接入方自检「Casdoor 可达 → 能换真实 token 且 claim 契约符合 → 后端验签生效」。
#
#   Layer 0  Casdoor discovery / JWKS 正常                     (只需 Casdoor 起着)
#   Layer 1  password grant 换到 token 且 sub/owner/groups 契约 (需 CASDOOR_CLIENT_ID/SECRET)
#   Layer 2  后端 OIDC resource-server 401/200 验签矩阵          (需 auth-platform-admin:8201 起着)
# 缺前置的层自动 SKIP(不算失败), 便于「有什么验什么」。
#
# 用法:
#   bash deploy/sso-smoke.sh                                   # 仅 Layer 0
#   CASDOOR_CLIENT_ID=... CASDOOR_CLIENT_SECRET=... bash deploy/sso-smoke.sh   # Layer 0-2
# 可选: CASDOOR_URL(默认 http://localhost:8000) CASDOOR_ISSUER(默认=CASDOOR_URL)
#       CASDOOR_ADMIN/CASDOOR_ADMIN_PW(默认 admin/123) AUTHZ_ADMIN(默认 http://localhost:8201)
#       ADMIN_SMOKE_PATH(默认 /admin/schema, 需 viewer/admin 组的 GET 端点)
set -uo pipefail

command -v jq   >/dev/null || { echo "需要 jq";   exit 1; }
command -v curl >/dev/null || { echo "需要 curl"; exit 1; }

CASDOOR="${CASDOOR_URL:-http://localhost:8000}"
ISSUER_EXP="${CASDOOR_ISSUER:-$CASDOOR}"
CID="${CASDOOR_CLIENT_ID:-}"
CSEC="${CASDOOR_CLIENT_SECRET:-}"
CUSER="${CASDOOR_ADMIN:-admin}"
CPW="${CASDOOR_ADMIN_PW:-123}"
ADMIN_SVC="${AUTHZ_ADMIN:-http://localhost:8201}"
ADMIN_PATH="${ADMIN_SMOKE_PATH:-/admin/schema}"

FAILED=0
SKIPPED=0
assert()  { if [ "$2" = "$3" ]; then echo "  PASS  $1  ($2)"; else echo "  FAIL  $1  got=[$2] want=[$3]"; FAILED=1; fi; }
skip()    { echo "  SKIP  $1"; SKIPPED=1; }

# base64url(JWT 段) -> JSON。macOS(-D) 与 GNU(-d) 兼容。
jwt_payload() {
  local p="${1#*.}"; p="${p%%.*}"      # 取中段 payload
  p="${p//-/+}"; p="${p//_//}"         # base64url -> base64
  case $(( ${#p} % 4 )) in 2) p="${p}==";; 3) p="${p}=";; esac
  printf '%s' "$p" | base64 -d 2>/dev/null || printf '%s' "$p" | base64 -D 2>/dev/null
}

# ───────────────────────── Layer 0: discovery / JWKS ─────────────────────────
echo "==> Layer 0  Casdoor discovery / JWKS ($CASDOOR)"
DISCO=$(curl -sf "$CASDOOR/.well-known/openid-configuration" 2>/dev/null || true)
if [ -z "$DISCO" ]; then
  echo "  FAIL  Casdoor 不可达 / discovery 取不到: $CASDOOR/.well-known/openid-configuration"
  echo; echo "❌ Layer 0 失败: 先起 Casdoor (cd deploy && docker compose up -d casdoor)"; exit 1
fi
ISS=$(echo "$DISCO"  | jq -r '.issuer   // empty')
JWKS=$(echo "$DISCO" | jq -r '.jwks_uri // empty')
assert "discovery.issuer 非空"   "$([ -n "$ISS" ]  && echo yes || echo no)" "yes"
assert "discovery.jwks_uri 非空" "$([ -n "$JWKS" ] && echo yes || echo no)" "yes"
[ -n "$ISS" ] && echo "        issuer=$ISS  (期望≈$ISSUER_EXP)"
NKEYS=$(curl -sf "${JWKS:-$CASDOOR/.well-known/jwks}" 2>/dev/null | jq -r '(.keys // []) | length' 2>/dev/null || echo 0)
assert "JWKS 至少 1 把公钥" "$([ "${NKEYS:-0}" -ge 1 ] 2>/dev/null && echo yes || echo no)" "yes"

# ───────────────────────── Layer 1: 换 token + claim 契约 ─────────────────────
echo "==> Layer 1  换真实 token + claim 契约 (sub/owner/groups)"
AT=""
if [ -z "$CID" ] || [ -z "$CSEC" ]; then
  skip "Layer 1/2 需要 CASDOOR_CLIENT_ID/CASDOOR_CLIENT_SECRET (未提供)"
else
  AT=$(curl -s -X POST "$CASDOOR/api/login/oauth/access_token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "grant_type=password&username=$CUSER&password=$CPW&client_id=$CID&client_secret=$CSEC&scope=openid" \
        | jq -r '.access_token // empty')
  if [ -z "$AT" ] || [ "$AT" = "null" ]; then
    assert "password grant 拿到 access_token" "no" "yes"   # 提供了凭据却换不到 -> 真失败
    echo "        (检查 client_id/secret、用户名口令、以及该应用是否开启 password grant)"
    AT=""
  else
    assert "password grant 拿到 access_token" "yes" "yes"
    PAYLOAD=$(jwt_payload "$AT")
    SUB=$(echo "$PAYLOAD"   | jq -r '.sub    // empty')
    OWNER=$(echo "$PAYLOAD" | jq -r '.owner  // empty')
    GTYPE=$(echo "$PAYLOAD" | jq -r '.groups | type' 2>/dev/null)
    NPERM=$(echo "$PAYLOAD" | jq -r '(.permissions // []) | length' 2>/dev/null || echo 0)
    assert "sub 非空(全局用户主键)"        "$([ -n "$SUB" ]   && echo yes || echo no)" "yes"
    assert "owner 非空(租户)"              "$([ -n "$OWNER" ] && echo yes || echo no)" "yes"
    assert "groups 是数组"                 "$GTYPE" "array"
    G0=$(echo "$PAYLOAD" | jq -r '(.groups // [])[0] // empty')
    if [ -n "$G0" ]; then
      if [[ "$G0" == */* ]]; then GP=yes; else GP=no; fi
      assert "groups 元素为全路径 <org>/<group>" "$GP" "yes"
    fi
    echo "        sub=$SUB  owner=$OWNER  groups[0]=${G0:-<none>}  permissions=$NPERM"
    [ "${NPERM:-0}" = 0 ] && echo "        note: permissions 为空属正常(业务 scope 需先跑 deploy/casdoor-seed.sh 并分配角色)"
  fi
fi

# ───────────────────────── Layer 2: 后端验签 401/200 矩阵 ─────────────────────
echo "==> Layer 2  后端 resource-server 验签矩阵 ($ADMIN_SVC)"
code() { curl -s -o /dev/null -w '%{http_code}' "$@" 2>/dev/null || echo 000; }
HEALTH=$(code "$ADMIN_SVC/actuator/health")
if [ "$HEALTH" != "200" ]; then
  skip "Layer 2 需要 auth-platform-admin 起着 (探活 $ADMIN_SVC/actuator/health = $HEALTH)"
elif [ -z "$AT" ]; then
  skip "Layer 2 需要 Layer 1 的合法 token (未取得)"
else
  # 大 token header-size 门禁: Casdoor JWT 常 8-9KB > Tomcat 默认 8KB。把大 token 打到 permitAll 的
  # /actuator/health: 200 = header 放得下且验签通过; 400 = Tomcat 在鉴权前就按 header 过大拒了。
  HB=$(code -H "Authorization: Bearer $AT" "$ADMIN_SVC/actuator/health")
  if [ "$HB" = "400" ]; then
    assert "合法 token 未被 Tomcat 按 header 过大拒(需放大 max-http-header-size)" "400" "200"
    echo "        修复: 给该服务加  server.max-http-request-header-size: 64KB  (当前 token ${#AT}B > 默认 8KB; Spring Boot 3 属性名)"
    echo "        Layer 2 其余断言跳过(header 门禁未过, 无法有效验签)"
  else
    assert "合法大 token 通过 header + 验签(/actuator/health)" "$HB" "200"
    # 篡改「结构合法但签名错」的 token(翻转签名段末字符, 长度不变): 期望 401(验签拦下)
    if [ "${AT: -1}" = "A" ]; then AT_BAD="${AT%?}B"; else AT_BAD="${AT%?}A"; fi
    assert "无 token → 401(需鉴权)"          "$(code "$ADMIN_SVC$ADMIN_PATH")"                          "401"
    assert "篡改签名 token → 401(验签拦下)"   "$(code -H "Authorization: Bearer $AT_BAD" "$ADMIN_SVC$ADMIN_PATH")" "401"
    C_OK=$(code -H "Authorization: Bearer $AT" "$ADMIN_SVC$ADMIN_PATH")
    # 合法 token 到受保护端点: 200=有权 / 403=验签过但当前组无此端点权限(authn 仍 OK), 均非 401。
    OKV=$([ "$C_OK" != "401" ] && [ "$C_OK" != "000" ] && echo pass || echo "fail")
    assert "合法 token 达受保护端点通过验签(非401, 实得 $C_OK, $ADMIN_PATH)" "$OKV" "pass"
    [ "$C_OK" = "403" ] && echo "        note: 403 = 验签通过但当前用户组无 $ADMIN_PATH 权限(登录接入本身 OK)"
  fi
fi

echo
if [ "$FAILED" != 0 ]; then
  echo "❌ 有断言失败"; exit 1
elif [ "$SKIPPED" != 0 ]; then
  echo "✅ 已跑的断言全过(有层被 SKIP: 补齐 CASDOOR_CLIENT_ID/SECRET 与 admin:8201 可跑全量)"
else
  echo "✅ SSO 接入冒烟全部通过 (Layer 0-2)"
fi
