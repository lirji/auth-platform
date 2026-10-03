# MG01 单一声明验证

状态：产品验证 PASS；全计划仍 ACTIVE。Commerce 代码 b109f20、文档 b34891d 已按持续授权正常推送任务分支并合入远程 main；精确 CI 正在跟踪。

- 实际 catalog.json 同时生成6组35经营页面与协作入口；44节点／123能力，原两兼容节点保留。与固定旧提交 c9eb50c 的名称、路由和顺序逐项比较 PASS，无可见界面变化。
- 四个 Node 行为测试 PASS：稳定ID重命名和路由变化、新增页面、非法引用／循环／排序冲突、旧能力语义与版本保护；CI已纳入测试。
- Auth 28个 Python 导出／出版测试 PASS，包括新声明消费分支；py_compile PASS。
- Commerce 前端真实类型检查／生产构建、共享声明构建检查、实际 Prettier、diff hygiene PASS；卫生门禁无阻断，统一formatter发现存在限制。
- 固定提交真实CLI与Auth导出器生成JSON完全相同：44菜单／123能力、同源码提交／原始字节摘要、auto_grants=false、auto_roles=false。新导出默认不依赖TS源码正则或固定35页数量。
- 未请求发布接口、登录或修改原目录v2／任何角色／Grant；未生产部署。候选与日志在两仓.local/menu-role-governance／.local/menu-catalog-source-*内保留。

恢复：MG02实施完整菜单差异和非法预览提示；不用旧目录发布流程重发此候选。远程CI终态另记录，不把本地PASS冒充远程成功。
