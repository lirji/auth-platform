# P5-01 TEST_RESULT

status=COMPLETED；gate=PASS。当前片为真实组织选择、应用目录与上下文壳层，不冒充P5完整业务试点。

- 后端admin/server依赖reactor编译/单测通过；PortalPostgresIT 5项真实PG通过：无关联应用不公开、管理委派不等于业务访问、当前成员有效性、双组织和稳定有界游标、跨租户游标重放不泄露、成员变化后拒绝迟到结果、依赖失败不返回部分允许。该PG测试中的图判权端口为受控测试端口，真实图另由浏览器链路覆盖。
- auth-console `pnpm build`通过；Node上下文/安全跳转3项测试通过。Code hygiene为IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，唯一工具限制FORMAT_TOOL_NOT_AVAILABLE（既有仓库未配置统一formatter）。首轮HTTP魔法数Finding已改用既有HttpStatusCode/具名测试状态常量。
- 本地真实Casdoor PKCE Token、两个组织的受邀PARTNER、独立PG、真实P3图投影与auth进程、浏览器完成[evidence/p5-01/ui-result.json](evidence/p5-01/ui-result.json)5项检查；HTTP准备和命令均真实，未拦截API伪造响应。源码脚本deploy/governance-p5-smoke.py与governance-p5-shell.mjs，私密日志目录`.local/governance/p5/shell-5286df4915`。
- UI覆盖：组织切换清旧上下文、同浏览器两个标签页使用不同tenant请求、合作成员无管理按钮且直调403、管理人员无业务入口但可读受保护分区状态；链接不带Token。
- 当前截图已由同一执行者实际查看：[桌面](evidence/p5-01/shell-member-1440.png)、[390窄屏](evidence/p5-01/shell-member-390.png)、[管理入口](evidence/p5-01/shell-management-1440.png)。组织下拉完整可读、长标签截断可展开、按钮同组一致、窄屏无页面横向溢出，管理表遵循原主题。实现后验证不冒充独立人员审查。

边界：本片使用真实PKCE生成Token注入OIDC sessionStorage；完整浏览器登录/SSO/回调测试属于P5-07，不能把注入会话说成已验证交互登录。图故障/撤权/业务商品与导出后续按P5-02至07验证。所有任务应用进程finally退出；复用P4专用基础设施仍运行，阶段收尾再停止，卷/配置保留。
