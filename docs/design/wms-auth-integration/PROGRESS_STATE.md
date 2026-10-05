# WMS Auth Integration Progress

## 任务目标

连续完成WMS只读、入出库/PDA、盘点调整与调拨的Auth集中权限接入、实际本机运行和正常Git交付。组织local-wms明确绑定ENT-DEMO；生产部署不在范围。

## 当前状态

W00–W04验证DONE；W04本地门禁PASS、两仓Git/精确CI待交付。W05 READY，后续W06/W07依次执行。全任务未完成。最终Git与CI事实以Auth私密 `.local/wms-auth-integration/delivery-result.json` 为准；缺失或未通过不能称已合入主线。

## 已完成

- W00：8602电商实际未启用IAM，5273治理不控制该实例；原WMS55文件与分支/HEAD保护，Driver/ERP不处理。
- W01：206单元、13SDK HTTP、2真实PG/图IT；Auth协议实现b2d2414，含检查点main7712d26/CI37213811393已发布。
- W02：94操作/43scope→49能力/16菜单，生成与漂移检查；WMS实现411d3db，含检查点远端main17048d5/CI37213828874已发布。
- W03：独立local-wms/ENT-DEMO、PKCE wms-central、五独立服务身份/私密配置；Auth main b66f735/CI37219575766已发布。
- W04：逐动作资源范围、本人接口、中央菜单/深链/按钮/子查询。最新隔离71a5c4d2296a的19身份/SQL+5浏览器+7企业-only后端+1企业-only浏览器+2撤权+9故障撤权PASS；真实页面已查看。工具reader-a与enterprise-only来源已撤销。
- W04前端39文件84项全套、类型/build通过。完整Java原303项有1启动失败；保留原失败及第二轮失败。用户要求继续后正常Docker恢复，135原容器ID/镜像/挂载/运行状态与36原运行服务健康核对，未删除卷/镜像。该失败案例专项150.2秒PASS，144 default必需检查PASS，test-support补验PASS。不声称整条reactor一次通过。
- W04有界Review通过。自动hygiene宽泛规则误分类测试/HTTP/原生Promise/有限资源类型，逐条审查按用户禁止机械常量化规范处理，原结果保留；无仓库格式化器的限制见[验证记录](W04_TEST_RESULT.md)。

## 未完成

- W04两仓完整逻辑提交、精确CI与main正常发布。
- W05内部机器发行方严格隔离与真实入出库/PDA；W06独立审批/应用和调拨源目标；W07跨Docker HTTPS、实际本机运行/故障/回退和最终交付。

## 下一步

复用两个feat/wms-central-authorization分支及WMS工作树 `/Users/liruijun/.local/share/git-worktrees/wms-platform/central-authorization`。W04交付后连续W05，不要求重复继续，不重新创建组织身份。正常Docker恢复已执行，勿重复重启。临时caffeinate session68556仅本任务；旧宿主fixture/Vite已停止，后续按当前切片启动自己的验收。
