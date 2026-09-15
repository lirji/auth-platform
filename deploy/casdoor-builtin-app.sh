# 从 Postgres 读取 Casdoor built-in 应用凭据。
# Casdoor 首次启动会随机生成 client_id，不能写死旧值 ea46d9a8033b0be2d8ed。
# 用法：. deploy/casdoor-builtin-app.sh && casdoor_load_builtin_app
# 成功后导出 BUILTIN_CID、BUILTIN_SECRET。若已设置 BUILTIN_CID 则按该 id 查 secret。

casdoor_load_builtin_app() {
  local container="${1:-${AUTHZ_POSTGRES_CONTAINER:-authz-postgres}}"
  local row cid secret
  if [[ -n "${BUILTIN_CID:-}" ]]; then
    cid="${BUILTIN_CID}"
    secret="$(docker exec "${container}" psql -U authz -d spicedb -tAc \
      "select client_secret from application where client_id='${cid}'" 2>/dev/null | tr -d '[:space:]')"
  else
    row="$(docker exec "${container}" psql -U authz -d spicedb -tAc \
      "select client_id || chr(9) || client_secret from application where name='app-built-in' and owner='admin' limit 1" 2>/dev/null)"
    cid="$(printf '%s' "${row%%$'\t'*}" | tr -d '[:space:]')"
    secret="$(printf '%s' "${row#*$'\t'}" | tr -d '[:space:]')"
  fi
  if [[ -z "${cid}" || -z "${secret}" ]]; then
    echo "无法读取 Casdoor built-in 应用 client_id/secret（容器 ${container}）" >&2
    return 1
  fi
  BUILTIN_CID="${cid}"
  BUILTIN_SECRET="${secret}"
  export BUILTIN_CID BUILTIN_SECRET
}

# 新装 Casdoor 的 app-built-in 默认 grant_types 为空，password grant 会 unsupported。
# 用控制台同款会话登录补上 password/authorization_code/refresh_token，再给后续脚本换 admin token。
casdoor_ensure_builtin_password_grant() {
  local casdoor="${CASDOOR_URL:-http://localhost:8000}"
  local admin="${CASDOOR_ADMIN:-admin}"
  local password="${CASDOOR_ADMIN_PW:-123}"
  local cookie app updated status
  cookie="$(mktemp)"
  status="$(curl -s -c "${cookie}" -X POST "${casdoor}/api/login" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg user "${admin}" --arg password "${password}" '{application:"app-built-in",organization:"built-in",username:$user,password:$password,autoSignin:true,type:"login"}')" \
    | jq -r '.status // empty')"
  if [[ "${status}" != "ok" ]]; then
    rm -f "${cookie}"
    echo "Casdoor 会话登录失败，无法校准 built-in grantTypes" >&2
    return 1
  fi
  app="$(curl -s -b "${cookie}" "${casdoor}/api/get-application?id=admin/app-built-in" | jq -c '.data // empty')"
  if [[ -z "${app}" ]]; then
    rm -f "${cookie}"
    echo "读不到 app-built-in" >&2
    return 1
  fi
  updated="$(printf '%s' "${app}" | jq -c '.grantTypes = (((.grantTypes // []) + ["authorization_code","refresh_token","password"]) | unique) | .enablePassword=true')"
  status="$(curl -s -b "${cookie}" -X POST "${casdoor}/api/update-application?id=admin/app-built-in" \
    -H 'Content-Type: application/json' -d "${updated}" | jq -r '.status // empty')"
  rm -f "${cookie}"
  if [[ "${status}" != "ok" ]]; then
    echo "更新 app-built-in grantTypes 失败" >&2
    return 1
  fi
}
