#!/usr/bin/env bash
# 供 auth-platform 与同级业务仓库启动脚本 source 的中央端口加载器。
# 不接受调用方通过同名环境变量覆盖，确保 platform-ports.env 是本地统一入口端口的唯一来源。

_platform_ports_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_PORTS_FILE="${PLATFORM_PORTS_FILE:-${_platform_ports_dir}/platform-ports.env}"

_platform_ports_fail(){
  printf '中央端口注册表错误: %s\n' "$*" >&2
  exit 1
}

[[ -r "${PLATFORM_PORTS_FILE}" ]] || _platform_ports_fail "无法读取 ${PLATFORM_PORTS_FILE}"

_platform_ports_keys='AUTH_PORTAL_UI_PORT LANGCHAIN4J_UI_PORT RECSYS_UI_PORT DROOLS_UI_PORT RISK_UI_PORT WORKFLOW_UI_PORT RECON_UI_PORT BENEFIT_UI_PORT MARKETING_UI_PORT'
_platform_ports_loaded=' '

# 只解析 KEY=整数，避免把 env 文件当作任意 shell 代码执行。
while IFS='=' read -r _platform_ports_key _platform_ports_value; do
  _platform_ports_key="${_platform_ports_key%%[[:space:]]*}"
  [[ -z "${_platform_ports_key}" || "${_platform_ports_key}" == \#* ]] && continue
  case " ${_platform_ports_keys} " in
    *" ${_platform_ports_key} "*) ;;
    *) _platform_ports_fail "未知配置项 ${_platform_ports_key}" ;;
  esac
  case "${_platform_ports_loaded}" in
    *" ${_platform_ports_key} "*) _platform_ports_fail "${_platform_ports_key} 重复定义" ;;
  esac
  [[ "${_platform_ports_value}" =~ ^[0-9]+$ ]] \
    || _platform_ports_fail "${_platform_ports_key} 必须是整数"
  (( _platform_ports_value >= 1 && _platform_ports_value <= 65535 )) \
    || _platform_ports_fail "${_platform_ports_key} 超出 1-65535"
  export "${_platform_ports_key}=${_platform_ports_value}"
  _platform_ports_loaded="${_platform_ports_loaded}${_platform_ports_key} "
done < "${PLATFORM_PORTS_FILE}"

_platform_ports_seen=' '
for _platform_ports_key in ${_platform_ports_keys}; do
  eval "_platform_ports_value=\${${_platform_ports_key}:-}"
  [[ -n "${_platform_ports_value}" ]] || _platform_ports_fail "缺少 ${_platform_ports_key}"
  case "${_platform_ports_seen}" in
    *" ${_platform_ports_value} "*) _platform_ports_fail "端口 ${_platform_ports_value} 重复" ;;
  esac
  _platform_ports_seen="${_platform_ports_seen}${_platform_ports_value} "
done

# Drools 的 OIDC 回调必须跟随统一入口端口，不能保留另一份硬编码。
export DROOLS_REDIRECT_URI="http://localhost:${DROOLS_UI_PORT}/ui/auth/callback"
export REDIRECT_GATEWAY="${DROOLS_REDIRECT_URI}"
export LANGCHAIN4J_CORS_ORIGINS="http://localhost:${LANGCHAIN4J_UI_PORT},http://127.0.0.1:${LANGCHAIN4J_UI_PORT},http://localhost:5173,http://127.0.0.1:5173,http://localhost:5273,http://127.0.0.1:5273,http://localhost:4173,http://127.0.0.1:4173"
export GATEWAY_CORS_ORIGINS="${GATEWAY_CORS_ORIGINS:-${LANGCHAIN4J_CORS_ORIGINS}}"

unset _platform_ports_dir _platform_ports_keys _platform_ports_loaded _platform_ports_key _platform_ports_value _platform_ports_seen
unset -f _platform_ports_fail
