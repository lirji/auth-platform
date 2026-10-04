# 菜单与角色治理本机 Docker 部署结果

任务 `DOCKER-MG-20261003`。用户在 MG00–MG19 交付后明确要求部署 Docker，本次更新既有本机 `auth-platform` 的三个治理应用。部署与必要验收已完成，`DEPLOYMENT_RESULT=PASS`。日期为 2026-10-03（America/Los_Angeles），完成时间为 2026-10-04 UTC。

| 服务 | 入口／用途 | 实际镜像 | 验证结果 |
| --- | --- | --- | --- |
| auth-console | http://localhost:5273/governance | `auth-platform/governance-console:rev-fd6bf5911981` | healthy；真实登录和页面通过 |
| governance-admin | console 共享回环 8201 | `auth-platform/governance-admin:rev-fd6bf5911981` | healthy；19 项认证读取通过 |
| governance-projector | POLICY／DIRECTORY 可靠投影 | 与 admin 同一镜像 | healthy；两类投影 READY，失败计数 0 |

部署源码为 `fd6bf5911981b711f77af2ef747943e471f569a8`。它与已通过 [CI37173735107](https://github.com/lirji/auth-platform/actions/runs/37173735107) 的 `d283ad7171ff8ff224517b2e7818e86542741576` 只有六个文档路径差异，产品、构建配置和工具源码相同。CI 精确 head、completed SUCCESS 及 28 个成功步骤已重新读取；没有将文档提交声称为新一轮 CI。

## 制品与数据库升级

控制台和后端均由当前源码进行 Docker 多阶段构建，沿用固定 Node 22、nginx 1.27、Java 21 镜像摘要及 pnpm 9.15.9。后端使用根 reactor 和 `-Dmaven.jar.forceCreation=true`，没有复制宿主旧 JAR。

- console 镜像 ID：`sha256:5ebbb031408fb63195d6d89d54869df279708aaa3666a90355e580aa8b04237d`。
- admin／projector 镜像 ID：`sha256:42704da91b1cfabbadbb94dbeca03bceb3d0faa1b27e66ef4a5a4f057eb9de13`。
- 实际运行 JAR SHA256：`3115b499bb875e41fb9fbd213eb5fa2b612e6935a572d9552c0989d19a7b7fd9`。

原 `auth_governance` 库先完成 `pg_dump`，再停止旧 admin／projector，使用候选镜像的 `GovernanceCli bootstrap` 和原已完成命令幂等执行。V21–V36 共 16 项迁移成功，最终版本 V36；含初始化记录共 37 条成功历史。升级前后原 44 张受保护表逐行相同。HTTP 和 worker 仍仅 validate；没有重写迁移、repair、重新初始化身份或重发目录。

部署前两个分区已经 BLOCKED，旧 projector unhealthy。确认依赖可读，且两个远端 marker 与保存的 SQL marker 一致后，通过真实管理 Token 调用原受审计 `retry-strict` 接口，每类一个固定命令。新 projector 实际恢复 POLICY 5/5、DIRECTORY 3/3，均 READY、failures=0。没有直接改 READY、替换 marker 或清空操作历史。

## 实际验收

- 三个运行容器镜像 ID 与候选不可变制品完全一致；私密 runtime.env 已绑定新标签，后续启动复用本次镜像。
- 原 Casdoor 授权码＋PKCE 登录成功；19 项认证读取通过，涵盖原管理与目录、发布历史、迁移来源、人员影响、复核、引用退出条件。匿名管理读取 401。机器发布默认关闭，后端入口拒绝 403；本地 UI nginx 未开放该前缀。
- 原目录仍为 v2、44 菜单、123 能力、21 资源；可授予集合仍为原 3 项。原角色、Grant、委派、范围、身份绑定及历史目录保持。
- 8 个实际 HTTP 静态文件与运行镜像逐字节一致；5 个 SPA 路径返回同一新版 HTML，HTML no-cache。运行 JAR 的 36 个迁移脚本与源码一致，包含新人员核对和人工复核类。
- 浏览器 8 组检查通过：42 个中文菜单来源名称及实际路由、角色迁移与历史任务、原 v2 发布历史、能力退役阻断、人员与完整来源、人工复核入口。治理管理请求写入 0、接口失败 0、脚本错误 0。
- 1440／390／320 视口共 12 张截图已逐张查看。宽表内部滚动、模态内容独立滚动，页面无横向溢出。
- 最终 38 张业务表逐行相同；审计、命令及诊断审计原记录保留，只增加两项严格投影重试和本次诊断记录。原目录指针仍为 2，没有新角色、Grant、迁移或复核任务。其余 125 个现存容器 ID 全部未变。

两项本地验收工具失误已保留：首轮把未开放机器发布前缀的 UI nginx 405 误当作后端结果，改为直接检查实际后端 403；数据摘要使用了不存在的 `current_version`，改为真实 `manifest_version` 后完整重新核对。没有为通过验收修改产品或放宽业务断言。

## 回退与使用边界

旧 `rev-b918d87969b0` 的 console／admin 镜像、原私密配置及数据库备份均保留。旧 admin 的只读 lookup CLI 在 V36 库上实际校验并解析原身份成功；没有实际切回旧 HTTP／worker，不能宣称完整回退演练已通过。健康失败时恢复原三个镜像与配置，保留追加迁移及数据；数据库恢复须另行评估，镜像回退不等于业务数据还原。本次部署成功，未触发回退。

页面导航为“应用管理 → 菜单权限／权限目录／角色管理／人员变更核对／权限复核”。登录资料沿用既有 `.local/docker-governance/ACCESS.md`，没有修改账号或密码。查看运行状态：

```bash
bash deploy/governance/run.sh status
```

此次只部署本机治理环境。原 8602 电商镜像、OA、门户与共享依赖未更新；本地示例分区不等于已接管电商实际业务鉴权。OA 消息分发器／真实 OA 引擎和生产运行证明仍未接入；人员页如实显示没有 OA 目录事实，旧发布未记录的来源保持 UNKNOWN，退役证明保持 UNPROVEN，生产部署未执行。

私密证据目录为 `.local/menu-role-governance/docker-fd6bf5911981/`：deployment-result、CI／镜像／迁移／健康／HTTP／静态字节／数据保护／浏览器／视觉检查结果、所有失败记录、原配置和备份。目录 0700、文件 0600，不入 Git。既有专库、回退制品和七个旧工作树继续保留。

## 2026-10-04 重新部署与菜单核对

用户要求重新部署并排查页面只有三个个人菜单。重新部署前运行镜像已经是上述 `rev-fd6bf5911981`；管理委派启用、成员有效。通过真实授权码＋PKCE 分别登录两个既有账号，管理员的 `management / catalog_owner` 均为 true，普通成员均为 false。两者的成员类型都是 EMPLOYEE，因此页面“内部成员”不能用于判断管理员。当前计算机连接没有可用浏览器，未读取用户截图中的具体会话账号，不能认定用户一定登录了哪个账号。

于 2026-10-04 15:07 UTC 完成 `bash deploy/governance/run.sh restart`，一起重建 console／admin／projector。三个容器 ID 均变化、镜像 ID 与上次验收完全相同，全部 healthy。此次没有产品或运行配置变化，无需构建新镜像或应用数据库迁移；部署前精确核对基线 `77ba1db27d6b66d5bc808338fcfc13f01de84771` 的 [Project Portal CI37175561588](https://github.com/lirji/auth-platform/actions/runs/37175561588) 已 completed success，该基线与镜像源码只有五个文档路径差异。

- 19 项认证读取通过；POLICY／DIRECTORY 均 READY，匿名读取 401，机器发布仍默认关闭并返回 403。管理员管理读取 200，普通成员管理读取 403。
- 独立浏览器实际登录：管理员的申请页显示全部 12 个导航入口，其中九个应用管理入口；普通成员仅显示三个个人入口。菜单页展示原 44 个菜单的 42 个中文名称，搜索“会员档案”能显示真实 `/operations/members` 路由；角色管理和成员授权页可打开。
- 浏览器五组检查、三张截图均通过，图片已实际查看；治理管理写请求 0、接口失败 0、脚本错误 0。没有改成员权限来显示菜单。
- 65 张治理表中 63 张完整记录相同，包含角色、授权、委派、成员身份、发布目录和迁移历史。诊断审计仅追加三条原记录保留；projection_stream 仅 worker_id、updated_at、lease_until、lease_generation 随新 worker 更新，其余值相同。八项私密配置文件摘要相同，其余 125 个容器 ID 相同。

验收工具首轮分别误用了不存在的 projector.properties 路径和未包含图标名称的精确链接定位，修正为实际双投影配置及链接可访问名称后重新验证通过；初次失败与截图保留，未更改产品或业务断言。私密证据保存在 `.local/menu-role-governance/redeploy-manager-navigation-20261004/`，目录 0700、文件 0600；上次部署证据、备份和回退镜像继续保留。

用户浏览器需要使用既有管理员会话才能显示“应用管理”。账号切换及入口说明已补充至[本机运行说明](../../deploy/governance/README.md#登录后只有三个个人菜单)。本次完成本机重建与页面验收，实际用户会话账号仍未验证；原有电商实际鉴权、OA 及生产接入边界沿用上文。
