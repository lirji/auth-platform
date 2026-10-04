# W03 明确组织与真实身份验证

日期：2026-10-04。Gate PASS，仅身份/目录/配置准备；业务权限、运行接管和 W04–W07 尚未完成。

- 用户明确选择独立 local-wms → ENT-DEMO，服务绑定 wms/local，五个服务使用不同私密凭据。
- 已通过项目现有 CLI 在 auth_governance 中创建五个明确成员；已有 Auth 管理主体仅增加 WMS Owner/管理委派，四个新业务主体没有 Grant。
- 新兼容 Casdoor 组织、wms-central PKCE 客户端与明确业务身份；未复制旧 WMS 或电商成员，未重置旧密码。发布源目录 v1：49 能力/16 菜单。
- 同一配置重复引导实际通过；固定命令/身份不重复创建，没有业务 Grant 副作用。
- 五个新 Python 边界测试通过：换企业/组织/tenant、复制成员、重新绑定 subject、复用服务凭据、旧客户端冲突、重复配置/不安全私密输入均拒绝。
- 新版 Auth admin/server/SDK reactor 同版构建与安装通过；Python 语法与 diff 检查通过。
- 六项真实 PKCE/中央 HTTP 验证通过：正确 Access Token 固定上下文；ID Token 401；外部 tenant、错误成员代际 403；请求伪造 app 400；错误服务凭据 401。只运行工具拥有的回环 server，结束后退出，未占用或重建旧服务。
- 原治理库 65 表、原 Casdoor 库 44 表已有行完整保留；只排除正在运行 projector 的 worker_id/lease_generation/lease_until/updated_at 四个已核实租约字段。新增分别 39/37 行属于本次身份、目录、委派和新登录记录。
- 私密备份已创建但没有恢复演练，不能作为灾备承诺。首次库名解析包含配置引号导致备份失败，修正后两库成功备份，原失败文件保留；没有在失败时执行引导。

证据目录：`.local/wms-auth-integration/w03-state/`；身份结果 `identity-smoke-*/result.json`，原数据核对 `backups/verified/original-data-preservation.json`。Token、口令、数据库配置不入 Git，未对生产部署。

```bash
python3 -m unittest discover -s deploy/tests -p 'test_governance_wms_provision.py' -v
python3 -m py_compile deploy/governance-wms-provision.py deploy/governance-wms-identity-smoke.py
python3 deploy/governance-wms-provision.py --state-directory .local/wms-auth-integration/w03-state --manifest <WMS>/docs/iam/catalog.json
python3 deploy/governance-wms-identity-smoke.py --state-directory .local/wms-auth-integration/w03-state
```
