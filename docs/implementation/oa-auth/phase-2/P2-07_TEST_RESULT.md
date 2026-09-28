# P2-07 菜单与状态展示验收

PASS。auth-console构建/类型检查成功；菜单父节点权限2项单测成功；真实管理与判权HTTP34项通过，新增本人菜单可见、无业务Grant的管理员菜单为空、撤权后菜单隐藏。

浏览器工具 `deploy/governance-ui-smoke.py --fixture <P2独立目录> --playwright-module <已安装@playwright/test路径>` 使用真实管理Token、真实admin/server/PG/图；没有拦截API或前端mock数据。6组操作通过：空表单校验、真实菜单与链接、普通成员管理403、切范围立即清除旧菜单、PENDING/图确认刷新、撤权隐藏且直接后端独立DENY。

证据 `.local/governance/p2/access-37bd71ee6522/ui/result.json`，截图同目录：member-1440、member-390-forbidden、manager-pending-1440、manager-active-1440、manager-390、member-revoked-390。已实际查看前四类中的桌面成员、桌面待生效管理、窄屏管理与窄屏403截图：遵循既有主题、长标识正常换行/表格横向滚动、表单与错误反馈无重叠，状态有文本。无新弹层/详情/写入表单；菜单链接是已登记应用入口。

生产同源反代治理路径通过nginx -t。正式OIDC注册与共享IdP升级保持P1限制；本轮页面验收覆盖真实会话后的治理交互，不将隔离Token夹具当成生产SSO上线证明。
