# 电商真实菜单目录验证

## 代码验证 PASS（CM01/CM02/CM03导出工具）
- 后端 `-pl auth-platform-admin -am -Pgovernance-it clean verify`：256单测、14真实PG测试，0失败/错误；使用新建专属auth_gov_p1_test库，不改本机业务库。
- 前端生产构建 PASS；21 Node测试 PASS。
- 导出工具2测试 PASS：42来源节点名称/顺序、保留旧能力和菜单、缺页/改变旧能力/错误版本拒绝。
- 同版改名与缺显示元数据冲突、事务审计失败同时回滚显示快照、中文显示响应、旧权限JSON/hash兼容、窄委派不扩权、损坏显示hash失败关闭均覆盖。
- 首轮迁移失败：编号18与既有迁移重复；未落库，修正为V20后clean验证PASS。首轮导出fixture共享引用失效已修正后PASS。失败日志保留，不伪造通过。
- `git diff --check` PASS；逐项源码审阅无阻断。复用现有栈，无依赖升级。仓库未配置formatter/linter命令，不声称这些工具通过。

## Runtime验证 PASS
- 精确产品提交 `b918d87969b0b415c134db5a45737a798d01bbdd`：[CI37096950007](https://github.com/lirji/auth-platform/actions/runs/37096950007) completed/SUCCESS，28步骤。
- 本机5273已运行同提交immutable console/admin，console/admin/projector全部healthy；V20受控迁移PASS。原123个其他现存容器ID（含历史停止实例）未变。
- 真实Owner HTTP preview/publish/同请求重试PASS：版本2，44节点（42真实来源节点+2旧项）、123能力（原122闭集+保留store.manage），21资源。只产生一条v2 PUBLISH审计、一条显示快照；未创建角色/Grant/委派。
- 5认证HTTP读取/匿名401 PASS；8个静态文件及4个SPA路径与immutable镜像字节匹配PASS。
- 迁移前后完整身份、成员、绑定、命令/身份审计逐行相同；发布前后及浏览器后Role/Grant/委派/范围与历史v1快照逐行相同。可授予集合仍原3项。
- 最终第三轮浏览器42检查PASS，36个真实页面全部通过菜单点击，含42来源中文名称、中文搜索、真实路由/能力计数、菜单过滤、角色/创建表单读取。管理写入0、脚本错误0。
- 1440/390/320共18截图全部实际视觉检查PASS，表格使用内部横向滚动，弹窗和长编码不造成页面横向溢出。

## 保留的失败与修正
- 首次部署漏了migration owner步骤，HTTP服务按设计仅validate而拒绝待迁移V20；已自动回退三服务并确认旧镜像healthy。随后用已有已完成bootstrap命令原样幂等重试应用V20，身份/授权数据逐行不变；第二次部署PASS。未重写历史迁移。
- 浏览器第一轮连续硬刷新36页时在活动预算遇到依赖503，失败日志/截图保留；独立5HTTP复查PASS。最终采用真实菜单点击并覆盖多个硬刷新、角色/表单读取，全部PASS；短暂503根因未做推断，不声称首轮通过。
- 第二轮测试错误假定旧store_reader应关联实际目录；真实目录使用store.directory.read，旧角色仅store.read。修正测试预期，未改权限语义、角色或Grant；第三轮PASS。

私密证据位于 `.local/commerce-menu-directory/`，不提交凭据或数据备份。
