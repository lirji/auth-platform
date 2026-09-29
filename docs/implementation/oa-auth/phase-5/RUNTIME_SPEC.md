# P5 隔离运行与配置

沿用P4专用PG15434、Kafka19492、Flowable18300及P3专用图18544；不修改共享Casdoor/正式业务数据。工作台smoke每次创建新的受控治理测试数据库，真实Casdoor Token与proof/数据库配置仅写.local/governance/p5下0600文件，绝不加入版本控制。

## 门户邀请

邀请入口仍受governance/access/invitations既有开关共同保护。治理私密配置可额外提供以下**示例占位**，未配置count时为0：

```properties
portal.invitation.count=1
portal.invitation.1.tenant-id=<已启用分区的租户UUID>
portal.invitation.1.application-id=<已注册应用>
portal.invitation.1.environment=<环境>
portal.invitation.1.membership-id=<显式邀请管理员的员工成员UUID>
portal.invitation.1.generation=1
portal.invitation.1.max-invitation-seconds=3600
portal.invitation.1.max-membership-seconds=86400
```

必须匹配同一当前EMPLOYEE及应用委派；配置不替代身份/管理检查。发行方固定为已有invitation.user.issuer，期限上限7天/365天，配置最多100条且同成员代际/分区不重复。权限移除可关闭对应配置并重启或撤销当前管理委派；不自动撤回已建立成员（走原生命周期）。固定静态快照没有动态刷新或双权威来源。

## 验收命令

```sh
python3 deploy/governance-p5-smoke.py --playwright-module <commerce/frontend/node_modules/@playwright/test>
python3 deploy/governance-p4-e2e.py --oa <oa-platform> --infra-env <dev-infra/.env> --p5-playwright-module <commerce/frontend/node_modules/@playwright/test>
```

前者启动auth18522、console15275，UI真实创建角色/授予/策略/申请/邀请，不模拟响应；丢响应试验先真实提交然后中断首个响应。后者复用P4跨进程整链，只在已有真实审批节点启用OA console15276，JWT方式，不使用DEV身份。测试实例单独允许Origin `http://127.0.0.1:15276`；默认CORS拒绝此端口是首次UI办理403的原因，不通过浏览器请求截获改写Origin绕过。

所有测试JVM/Vite退出时关闭；P4基础容器在阶段完成后停止保留卷。此处启动属于授权隔离验证，不等于生产部署。P5-07仍须另行验证完整交互式OIDC登录与应用SSO。邮件/短信未配置，不声称已验证。
