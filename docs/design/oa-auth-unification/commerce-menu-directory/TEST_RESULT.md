# 电商真实菜单目录验证

## 代码验证 PASS（CM01/CM02/CM03导出工具）
- 后端 `-pl auth-platform-admin -am -Pgovernance-it clean verify`：256单测、14真实PG测试，0失败/错误；使用新建专属auth_gov_p1_test库，不改本机业务库。
- 前端生产构建 PASS；21 Node测试 PASS。
- 导出工具2测试 PASS：42来源节点名称/顺序、保留旧能力和菜单、缺页/改变旧能力/错误版本拒绝。
- 同版改名与缺显示元数据冲突、事务审计失败同时回滚显示快照、中文显示响应、旧权限JSON/hash兼容、窄委派不扩权、损坏显示hash失败关闭均覆盖。
- 首轮迁移失败：编号18与既有迁移重复；未落库，修正为V20后clean验证PASS。首轮导出fixture共享引用失效已修正后PASS。失败日志保留，不伪造通过。
- `git diff --check` PASS；逐项源码审阅无阻断。复用现有栈，无依赖升级。仓库未配置formatter/linter命令，不声称这些工具通过。

## Runtime验证待执行
- 精确提交CI、immutable console/admin构建与本机5273更新。
- 真实Owner发布44节点/123能力（保留store.manage与products/stores）与中文菜单只读浏览器验证。
- SQL逐行比较原Role/Grant/委派/范围、历史快照，以及公共依赖实例保留。

私密证据位于 `.local/commerce-menu-directory/`，不提交凭据或数据备份。
