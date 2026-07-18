#!/usr/bin/env bash
# casdoor-hide-business.sh — 隐藏 Casdoor 控制台侧边栏「商业」模块（产品/优惠券/购物车/订单/支付/计划/定价/订阅/交易等）。
# -----------------------------------------------------------------------------
# 原理：Casdoor 组织级 Organization.navItems 白名单。null 或含 "all" = 全部显示；设为显式 key 列表后，
# 前端 ManagementPage 按【叶子 key】过滤菜单，子项全被滤掉的分组整组消失（分组 key 仅供 org 编辑页勾选树回显）。
# 本脚本把所有 org 的 navItems 覆盖为「除商业分组外的全部菜单 key」。
#
# ⚠️ key 清单对齐 casdoor v3.115.0 web/src/common/NavItemTree.js —— 升级 Casdoor 若新增菜单项，
#    需把新 key 补进 NAV_KEEP，否则新菜单默认被隐藏（白名单语义）。
# 幂等：每次全量覆盖，可重复跑。新租户由 casdoor-tenant-provision.sh 建 org 时继承 built-in 的 navItems。
# 恢复全部菜单（含商业）：RESTORE=1 bash deploy/casdoor-hide-business.sh
# 用法：bash deploy/casdoor-hide-business.sh
# 可选(env)：CASDOOR_URL(默认 http://localhost:8000) CASDOOR_ADMIN/CASDOOR_ADMIN_PW(默认 admin/123)
# 依赖：curl + jq（admin 会话登录，无需 client 凭据）。注释一律中文。
set -euo pipefail

CASDOOR="${CASDOOR_URL:-http://localhost:8000}"
ADMIN="${CASDOOR_ADMIN:-admin}"
ADMIN_PW="${CASDOOR_ADMIN_PW:-123}"
RESTORE="${RESTORE:-0}"

command -v jq >/dev/null || { echo "需要 jq" >&2; exit 1; }

# 白名单：全部菜单 key，唯独去掉「商业」分组(/business-top)及其子项
# (/product-store /products /coupons /cart /orders /payments /plans /pricings /subscriptions /transactions)。
NAV_KEEP='[
  "/home-top","/","/shortcuts","/apps",
  "/orgs-top","/organizations","/groups","/users","/invitations",
  "/applications-top","/applications","/providers","/resources","/certs","/keys",
  "/sites-top","/agents","/servers","/server-store","/entries","/sites","/rules",
  "/roles-top","/roles","/permissions","/models","/adapters","/enforcers",
  "/sessions-top","/sessions","/records","/tokens","/verifications",
  "/admin-top","/sysinfo","/forms","/syncers","/webhooks","/webhook-events","/tickets","/swagger"
]'
[ "${RESTORE}" = "1" ] && NAV_KEEP='["all"]'

# admin 会话登录（控制台同款接口），cookie 存临时文件用完即删
COOKIE=$(mktemp)
trap 'rm -f "${COOKIE}"' EXIT
curl -s -c "${COOKIE}" -X POST "${CASDOOR}/api/login" -H 'Content-Type: application/json' \
  -d "{\"application\":\"app-built-in\",\"organization\":\"built-in\",\"username\":\"${ADMIN}\",\"password\":\"${ADMIN_PW}\",\"autoSignin\":true,\"type\":\"login\"}" \
  | jq -e '.status=="ok"' >/dev/null || { echo "Casdoor admin 登录失败（检查 ${ADMIN} 口令）" >&2; exit 1; }

ORGS=$(curl -s -b "${COOKIE}" "${CASDOOR}/api/get-organizations?owner=admin" | jq -r '.data[].name')
[ -n "${ORGS}" ] || { echo "查不到任何 organization" >&2; exit 1; }

for org in ${ORGS}; do
  OJ=$(curl -s -b "${COOKIE}" "${CASDOOR}/api/get-organization?id=admin/${org}" | jq -c '.data // empty')
  [ -n "${OJ}" ] || { echo "  跳过 ${org}: 读取失败"; continue; }
  curl -s --fail-with-body -b "${COOKIE}" -X POST "${CASDOOR}/api/update-organization?id=admin/${org}" \
    -H 'Content-Type: application/json' \
    -d "$(printf '%s' "${OJ}" | jq -c --argjson nav "${NAV_KEEP}" '.navItems=$nav')" \
    | jq -c --arg org "${org}" '{org:$org,status,msg}'
done

if [ "${RESTORE}" = "1" ]; then
  echo "done: 所有 org 的 navItems 已恢复为 [\"all\"]（全部菜单可见）"
else
  echo "done: 所有 org 已隐藏「商业」模块（刷新控制台页面生效）"
fi
