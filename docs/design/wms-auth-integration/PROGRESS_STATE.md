# WMS Auth Integration Progress

## 任务目标

连续完成W00–W07，local-wms绑定ENT-DEMO。WMS保留数据所有权；Auth管理成员/角色/权限/范围。生产不在范围。

## 当前状态

W00–W06本地验证完成，W07准备本机Runtime。Auth W04/W05分别8189b13/be49acc已精确CI通过并正常发布。WMS W04 876b13c/CI37246311489实际FAIL于旧序列登记进程测试，尚未发布；W05 fd722b4本地提交、W06累计CI必须全通过才正常合入main。所有后续Git事实以私密delivery-result.json为准，不把本地PASS当远程CI成功。

## 已完成

- W00–W03的协议/目录/组织/身份已发布，固定SDK源7712d26。94操作/43源scope→49能力/16菜单，权限hash不变。
- W04真实身份/SQL/企业-only/菜单深链/故障撤权与84UI通过，完整Java启动失败原记录保留，正常Docker恢复后专项及144门禁通过。135原容器/36运行服务恢复，勿再次重启。
- W05真实23身份/SQL+34动作+4PDA/5导航+10写撤权+15故障读撤权，安全21/Owner HTTP8/147门禁/UI10构建通过，8图实看；详见[W05_TEST_RESULT](W05_TEST_RESULT.md)。
- W06真实23身份/SQL、94控制（原DRAFT检查点恢复）、5PKCE浏览器、4控制来源撤权；MySQL盘点/调拨6项、安全21、UI10构建通过。完整49能力复核补齐写按钮，源/目的绑定Owner仓。详见[W06_TEST_RESULT](W06_TEST_RESULT.md)。9张最终图实看，2额外视觉来源已撤销；原旧Token两项403、5导航和9当前故障读撤权PASS。

## 未完成

- W06逻辑提交/累计CI与Git发布。WMS原失败SHA不能直接发布。
- W07当前Docker源码镜像/TLS/独立凭据、原库备份、实际本机更新/故障/旧机器兼容/回退、最终交付。

## 下一步

复用两个feat/wms-central-authorization分支及既有WMS任务树，不动原dirty工作/Driver/ERP。完成W06视觉来源撤销和当前浏览器，再提交并正常推累计CI。W07复用唯一Compose体系，SDK只HTTPS，UID10001+600分服务卷，不取消TLS/放宽HTTP。当前本机尚未切换，生产未授权。原fixture与v2 helper/Vite结束验收后正常停止；原测试DB/证据/工作树保留。根CODEX_PROGRESS包含具体session与私密恢复入口。
