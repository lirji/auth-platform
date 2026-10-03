# 页面权限验收结果

2026-10-02，GP01/GP02 产品及 GP03 隔离验收：PASS。本机部署/CI结果见 PROGRESS_STATE。

- Node 三组行为测试：19 PASS；生产 TypeScript/Vite 构建 PASS；admin 同源包构建 PASS；diff check PASS。
- 最终真实隔离运行 `runtime-2daeca80ef7a`，进程 exit 0，17 浏览器检查及6 HTTP/持久化检查 PASS。真人密码/PKCE，无注入登录令牌或模拟成功接口。
- 覆盖菜单树/URL恢复、能力筛选与真实空结果、完整固定角色及资源、复制预填、未提交保护、真实提交丢响应后同命令重试、202待生效、原授权来源回收、策略、普通成员403、窄委派上限、传输失败隐藏旧目录并重试。
- 最终 SQL：3角色、1授权、1范围、1策略；所选能力 `commerce.member.read`，资源 `commerce_member`、范围 `TENANT_ALL`。该隔离授权已真实回收；本机业务数据未作为测试写入目标。
- 33截图（1440/390/320）全部查看。新菜单/目录/角色详情/角色表单/授权/诊断布局通过，320无页面级横向溢出，宽表格内部滚动。部分策略/审计截图采集早于加载完成，未以空遮罩/加载截图宣称详情视觉通过；本机只读验收补查。
- 源码与最终 runner 记录的 frontend_sources SHA-256逐文件一致。全部私密制品指纹、SQL、浏览器回执和图片保留在 `.local/integrated-resource-selector/runtime-2daeca80ef7a/`，不提交凭据。
- Code Hygiene 无阻断；结果 IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS：仓库未配置统一 formatter/static analyzer。未声称已运行不存在的检查工具。

早期测试失败（选择器中文空格、失败注入时机、截图采集稳定性）及专用数据库保留，未覆盖或合并成最终 PASS。无后端/迁移/API契约或依赖版本变更。

## 本机更新后最终验收

`c101b45`精确CI 28步骤全PASS。最终只读浏览器第二轮 exit0，14检查/39截图，加入弹层所有父节点opacity及动画结束检查；39图全部实际查看PASS，桌面策略表单和已加载审计补证完成。第一轮截图仍保留。最终业务读取前后一致、0管理写入、0脚本错误、匿名401、8静态文件逐字节PASS；三应用healthy，原admin镜像及32其他实例保持。当前local真实2菜单/3能力，没有迁入隔离test目录。

产品代码自隔离验收至本机运行未变；此轮仅增加交付文档与私密只读验收证据，不追加产品/依赖/数据库修改。
