# P2 本人菜单与最小状态页面契约

页面 `/governance` 使用已有管理 OIDC 会话，不能用旧 Casdoor 组推断治理权限。查询企业、应用和环境；所有数据来自后端，改变范围、加载失败立即清除旧结果。业务接口仍逐请求判权。

GET `/api/governance/v1/me/access?tenant_id=<UUID>&application_id=<code>&environment=<code>`：本人有效成员校验后返回 `menus[{code,parent,href}]` 与 `capability_hints[string]`。href只来自登记应用入口与已发布清单路由；只为组织子菜单而显示的祖先href为null。仅当前主体，不提供代查。响应是当前提示，无持久缓存、无授权票据。

管理状态沿用 CONTRACTS_P2_ACCESS 的 `/access/state`，必须当前成员且有对应分区委派。页面独立显示403，不把无管理权解释为空授权集合。角色/授权游标分别推进。PENDING=待生效，ACTIVE=图已确认，REVOKED=已撤销；ACTIVE不是有效期/成员状态的替代判断。

`authz.governance.presentation.enabled=true` 与已有两开关同时开启才暴露菜单；admin私密配置必须指定独立graph.http/key。依赖/待投影失败503，明确无能力返回空菜单，403/401保持身份边界。一次展示预算10秒，不输出部分允许结果。

视觉沿用现有Ant Design主题与PageHeader：企业工作台、范围表单、入口菜单、只读授权表；不引入P5完整管理交互。桌面1440与窄屏390验证；长UUID允许表格横向滚动。默认视觉复用现状，未声称用户审稿。
