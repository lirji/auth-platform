# auth-console 实施计划(前端管控台)

> 由 frontend-plan 工作流产出:勘察 his-web/capability-showcase + 并行五方向子代理(需求/UIUX/架构/测试风险)+ **独立评审(已采纳)**。**最终计划获批前不写任何前端代码。**

## 评审采纳的修正(v1.1,读这一节即可掌握最终口径)

独立评审对照真实后端源码核实后,以下修正**覆盖下文相应描述**:

**Must-fix(进实施前必须闭环)**
1. **M0 硬退出标准**:必须用 curl 换一枚**真实 Casdoor access_token**,断言其中含**归一化(shortName)后的 `authz-admin`/`authz-viewer` groups claim**。这是全套菜单/路由 gating + 方法级授权的地基——Casdoor 默认可能不把 groups 放进 JWT(常只在 UserInfo),需在应用侧显式配 token fields。**不通过则前端 gating 全断,不得进 M1。**
2. **P0-A 加固补齐**:①引入 resource-server 后 Spring Security 默认拦全部端点 → 必须显式 `permitAll` `/actuator/health` 与 webhook 通道(his-platform 踩过);②webhook 需 **timestamp+nonce 防重放 + 对原始字节算 HMAC(用 ContentCachingRequestWrapper)+ 限流**,不能只 HMAC;③Bearer 明确送 **access_token**(非 id_token),校验 `aud` 含 console client_id;④group claim 做 shortName 归一化(与 CasdoorClient 一致);⑤issuer-uri 与 token `iss` 逐字符一致;⑥更新 server/admin 现有 smoke 脚本以适配 security 上下文。
3. **调试器 caveated 标注**:`lookupSubjects` **完全不过滤 permissionship**(比 lookupResources 更失真),条件命中会被当确定允许 → ⑤反查结果必须显式标注。

**Should-fix(采纳)**
4. **提级 `/admin/directory`(id→人名)**:`subjectField=id` ⇒ 主体是 Casdoor UUID,让管理员裸输 UUID 几乎不可用 → directory 从 nice 提到 should;调试器"检查我自己"从 token `sub` 自动带出。
5. **GrantForm 只做主体关系(v1)**:`parent_org/parent_space/parent_folder` 的主体是另一个对象、无 #member 形态,SubjectRefInput 表达不了 → **本期不在授予表单支持结构关系**(层级由业务侧建文档时双写),只授予 user/group 主体关系。
6. **删除"ZedToken→at_least_as_fresh"机制**:`/admin/check` 无 consistency/token 入参且锁死 fullyConsistent(读写天然一致),该机制多余且自相矛盾。**D8 改述为"维持 AdminController 现状 + 合同测试冻结不变量"**(非新增)。
7. **鉴权工时纠正**:his-web 实际只有 3 件可抄(config.ts 的 userManager、OidcAuthProvider、AuthBridge);**ProtectedRoute + 纯函数 guard + axios 401 单飞续期需新写**(capability-showcase 那套是 Vue,不可直搬)。M2 按此估工。
8. **D5 默认切 refresh_token**(offline_access + PKCE 公有客户端 + 轮换):iframe 静默续期依赖被现代浏览器普遍拦截的第三方 cookie;iframe 降为可选。
9. **撤销 group 授予**必须原样带 `subjectRelation=member`,否则删的是不存在的非 userset 元组→静默 no-op。

**v1 范围收敛(内部、仅平台管理员)**
- **v1 必交(核心闭环)**:登录/回调/登出 + ④授予 + ⑤调试器 + ②schema 查看器(静态 .zed 兜底即可)+ ⑥身份同步(后端已就绪,单按钮+diff,极便宜)。
- **v2 后置**:⑦审计(需后端接 platform-audit)、`/admin/expand`(引擎量大,D7 降级已够)、`/admin/relationships`(授予页用 per-permission 反查降级撑住)、lookup 的 limit/cursor(内部数据量小)。
- **合并**:③空间管理并入⑤调试器的一个 tab(与 lookupSubjects 高度重叠),省一页。
- **v1 只需新增后端读端点 `/admin/schema`(+建议 `/admin/directory`)**,其余 P0-C 端点进 v2。

**已知雷(记录/告警)**:`GroupSyncService` 用**短组名**做 SpiceDB group id,两个租户各有 "engineers" 会**合并成同一组**——console 需告警;antd 锁 `>=5.9` 用 Table `virtual`;"授予→反查一致"不变量的**真实 SpiceDB 集成测试从可选提为必跑**(复用 spicedb-smoke 基建)。

---

## Context / 目标 / 非目标

**Context**:auth-platform 的 Phase 0-2 + Phase 3 后端(admin 授权管理 API + Casdoor 组同步)已完成并验证。缺 **授权侧管控台前端 auth-console**:给管理员可视化管理 SpiceDB 关系、授予/撤销、以及 SpiceDB 没有的**权限调试器**。

**Goals**
- 独立 SPA(React18+Vite+TS+antd),登录走 Casdoor SSO(吃自家狗粮)。
- 覆盖 7 页:概览 / 授权模型查看器 / 空间管理 / 授予管理 / **权限调试器(核心)** / 身份同步 / 审计。
- 授权侧专注:身份/组织/用户/组的增删归 Casdoor 自带控制台,auth-console 只做**关系/授予/判定**。

**Non-Goals**
- 不做用户/角色/组织 CRUD(Casdoor 负责)。
- 不做租户级 scope 隔离(本期仅面向平台/安全管理员;多租户自助授权后置)。
- 不引入重型图库(schema 可视化用 antd Tree/Card;依赖图为可选增强)。

---

## ⚠️ 后端前置(P0 阻断项,前端联调前必须完成)

子代理一致标注:**admin(:8201)目前零鉴权、webhook 无验签、lookup 无上限**。admin 唯一消费者就是本 console(业务侧走 sdk→server:8200,不碰 admin),故加固**不影响任何已上线服务、爆炸半径极小**。

**P0-A · admin 加固(auth-platform-admin)**
1. 加 `spring-boot-starter-oauth2-resource-server`,`issuer-uri`→Casdoor(:8000),JWKS 惰性验签(校验 iss/aud/exp)。
2. 方法级授权:读端点(check/lookup/schema)需已登录 + `authz-viewer`;写端点(grant/revoke/casdoor-sync)需 `authz-admin`(映射自 Casdoor token 的 groups/roles claim)。
3. `/admin/casdoor/webhook` 改 **HMAC 共享密钥验签**(机器回调,独立于用户 token 通道)。
4. **同源反代优先**(见决策 D3):dev 用 Vite proxy,prod 用 nginx;若必须跨域再加显式白名单 CORS(`allowedOrigins` 精确列、`Authorization` 头、`allowCredentials=false`,因用 Bearer 非 cookie)。

**P0-B · Casdoor 注册 auth-console 应用**
- 新建 OIDC **公有 SPA 客户端**(PKCE,无 client_secret):redirect `http://localhost:5273/callback`(dev)+`:8202/callback`(prod);post_logout `/`;grant `authorization_code`(+`refresh_token` 若用);scope `openid profile`(+`offline_access`)。
- 建组 `authz-admin`/`authz-viewer`,token 以 `groups`/`roles` claim 承载。
- 把 console origin 加入 Casdoor 允许来源(token/userinfo/jwks 是 XHR 需 CORS)。

**P0-C · admin 补读端点(让页面从"降级"到"好用",可与前端并行)**
| 端点 | 用途 | 引擎能力 |
|---|---|---|
| `GET /admin/schema` | ②schema 查看器(透传 SpiceDB ReadSchema 文本) | 需给 AuthzEngine 加 readSchema |
| `GET /admin/relationships?resource=…` | ④列某资源现存关系元组 | 需给 AuthzEngine 加 **readRelationships** |
| `POST /admin/expand` 或 check 带 debug | ⑤解释"为何 allow"(判定路径) | 需给 AuthzEngine 加 **expand** |
| lookup 加 `?limit=&cursor=` + 截断标志 | ⑤大结果集 | SpiceDB optionalLimit |
| `GET /admin/audit` + 审计落库 | ⑦审计 | admin 接 platform-audit |
| `GET /admin/directory?ids=…` | id→人名(代理 Casdoor get-users/groups) | Casdoor API |

> 缺失端点前,对应页面**降级**(见各页"降级"),不阻塞其余页面上线。

---

## 决策记录(Decision Record)

| # | 决策 | 选择 | 备选 / 理由 |
|---|---|---|---|
| D1 | 框架/UI | **React18 + Vite5 + TS5 + antd5** | 与 his-platform/his-web 同构,鉴权"五件套"可照抄;次选 ant-design-vue(复用 langchain4j Vue 基建,但偏离既定栈) |
| D2 | 数据层 | **React Query(TanStack Query)** | his-web 用裸 axios+useState;但 console 数据密集(授予后需 invalidate 反查、缓存、分页),React Query 更合适。**这是相对 his-web 的新增**,记为有意偏离 |
| D3 | 跨域 | **同源反代**(dev Vite proxy /admin→8201;prod nginx),不开 admin CORS | Bearer 非 cookie,同源反代 dev/prod 对称、零 CORS 配置;不反代 Casdoor(保 issuer 一致性,authority 直连:8000) |
| D4 | token 存储 | **内存 + oidc userStore(sessionStorage)**;PKCE 瞬态(state/nonce/verifier)放 sessionStorage 回调后清 | 纯内存刷新掉登录体验差;localStorage 明文长存 XSS 风险大。折中并配 CSP + 401 单飞续期 |
| D5 | 续期 | 本地/同站 **iframe silent renew**;跨站切 **refresh_token(offline_access)** | 用 `VITE_OIDC_SCOPE` 一开关切换,代码路径统一 |
| D6 | schema 来源 | **GET /admin/schema 权威**(P0-C);未就绪时前端内置 knowledge.zed 静态副本(标注版本+漂移风险) | 避免可视化误导授权 |
| D7 | Playground 深度 | 有 expand(P0-C)→真实判定路径;无则**降级**为 schema 推导路径 + 逐段 lookup 佐证 + 醒目标注"非引擎 trace" | 不把可落地寄托在未确认字段 |
| D8 | 一致性 | admin 三处读锁死 `fullyConsistent`(管理面看最新) | **不变量**:绝不能为省延迟改 minimize_latency,否则破坏"授予→反查一致"(合同测试冻结) |

---

## 路由与页面流

| 菜单 | path | 权限 | 主端点 | 缺口降级 |
|---|---|---|---|---|
| 概览 | `/` | viewer | actuator/health + casdoor/sync 回显 | 无统计端点→规模卡"暂不可用" |
| 授权模型 | `/schema` | viewer | GET /admin/schema* | 未就绪→内置静态 .zed |
| 空间管理 | `/spaces` | viewer | GET /admin/resources/space/{id}/subjects | 无"列全部 space"→按 id 查/按管理员反查 |
| 授予管理 | `/grants` | admin | POST /admin/grants · /grants/revoke | 无"列资源全部元组"→按 permission 维度反查拼装(或 P0-C 的 relationships) |
| **权限调试器** | `/playground` | viewer | POST /admin/check · 两个 lookup GET | 无 expand→路径降级(D7) |
| 身份同步 | `/sync` | admin | POST /admin/casdoor/sync | 无历史→仅显本次结果 |
| 审计 | `/audit` | viewer | GET /admin/audit* | 未就绪→占位"待后端" |
| 登录/回调 | `/login` `/callback` | 公开 | Casdoor OIDC | — |

页面流详见各子代理产出;核心两条:
- **授予流(④)**:选 resource(type+id)→ RelationSelect(依 resourceType 动态:space→owner/admin/editor/commenter/viewer)→ subject(type+id;**type=group 时自动置 subjectRelation=member** 组成 userset)→ **实时元组预览** `document:tid_doc#editor@group:eng#member` → POST /admin/grants → 就地"在调试器验证"。撤销同表单走 /grants/revoke。
- **调试流(⑤)**:Check(subject+permission+resource+一致性)→ AllowDenyResult 大卡(绿 ALLOW/红 DENY)+ 判定路径;lookupResources(某主体能看哪些)/lookupSubjects(谁能看某对象)反查佐证;跨模式一键带参。

---

## 组件树

```
<App> providers: ConfigProvider(zhCN,theme) · QueryClientProvider · OidcAuthProvider(react-oidc-context) · SchemaProvider
├─ AppLayout(Layout): Header(账户菜单/登出) · Sider(Menu 按角色过滤) · Content(Breadcrumb+Suspense+Outlet)
├─ pages/: Overview · SchemaViewer · Spaces(+SpaceDetailDrawer) · Grants(+GrantForm+RecentGrantsList)
│          · Playground(+CheckPanel+LookupResources/SubjectsPanel+RelationPathView) · IdentitySync(+SyncDiffTable) · Audit
├─ components/domain/(原子,全站复用):
│    ObjectTypeSelect · ResourceRefInput · SubjectRefInput(userset #member 开关) · RelationSelect(动态) · PermissionSelect(动态)
│    · RefBadge/TupleText(彩色 chip) · AllowDenyResult(Result) · RelationPathTree(Tree) · SchemaTypeCard · PermissionExpansionTree
│    · SubjectsTable/ResourcesTable(游标分页+虚拟滚动+截断条) · DangerPopconfirm
├─ auth/(照抄 his-web 五件套): userManager · OidcAuthProvider · AuthBridge · ProtectedRoute · guard(纯函数,可单测)
├─ store/: authStore(镜像 user/scopes/status) · uiStore(主题/折叠)
├─ api/: client(axios+Bearer+401单飞续期+humanizeError) · authz · casdoor · schema · audit
├─ hooks/: useSchema · useCheck · useLookup(cursor) · useGrant · useReconcile · usePaginatedList
└─ domain/: lexicon(对象/权限/关系→中文+色+图标,单一来源) · zedParser(正则解析,失败降级原文) · refFormat(parse/format tuple)
```

**统一语言 lexicon**(依真实 `knowledge.zed`):对象 user/group/organization/space/folder/document;relation(可授予)owner/admin/editor/commenter/viewer/member/public_viewer/parent_*;permission(可判定)view/comment/edit/manage/membership/administrate。**relation≠permission**:表单选 relation、验证/调试用 permission,UI 内部转换。

**原子契约**:`ResourceRef={type,id}`、`SubjectRef={type,id,relation?}`(relation 存在即 userset)、`Tuple={resource,relation,subject}`;`refFormat.format(tuple)` 全站统一渲染。

---

## 交互状态与边界

- 四态(loading/empty/error/success)统一由 React Query 驱动;写成功后 invalidate 相关反查缓存即可(check 锁死 fullyConsistent,读写天然一致,无需水位——见评审修正 #6)。
- **大结果集**(lookup 可能上千):游标分页(默认 200)+ 硬上限截断 Alert + antd Table virtual + 输入防抖 300ms + AbortController 取消在途。
- check 用 fullyConsistent(D8);错误(资源不存在)→红 Alert 而非误判 deny。
- ⚠️ `SpiceDbAuthzEngine.lookupResources` 把 permissionship 空也当 HAS_PERMISSION → 未来 caveated 命中会被当确定允许,调试器需标注。

---

## API 契约(前端依赖)

**已就绪**(AdminController,见 §决策):/admin/grants、/grants/revoke、/resources/{t}/{id}/subjects、/subjects/{t}/{id}/resources、/check、/casdoor/sync、/casdoor/webhook。
**待后端补**(P0-C):/admin/schema、/admin/relationships、/admin/expand、lookup 的 limit/cursor、/admin/audit、/admin/directory。前端 `api/*` 按契约封装,缺失端点走降级分支且集中在 hooks 层便于后续切换。

---

## 文件级改动

**新建目录** `auth-platform/auth-console/`:`package.json`(pnpm,React18/antd5/@tanstack/react-query/oidc-client-ts/react-oidc-context/zustand/react-router-dom/axios)、`vite.config.ts`(port 5273,proxy /admin→8201)、`.env.example`、`env.d.ts`、`tsconfig*`、`index.html`、`Dockerfile`+`nginx.conf`(8202,同源反代 /admin,SPA 回退,silent-renew.html 放行)、`public/silent-renew.html`、`src/**`(见组件树)。
**照抄源**:his-platform/his-web 的 `auth/*`、`store/auth.ts`、`components/ProtectedRoute.tsx`、`api/client.ts` 拦截器、`layout/*`、`theme/*`、`main.tsx`/`App.tsx` 骨架、`vite.config.ts` proxy、`Dockerfile`/`nginx` 模板;capability-showcase 的 401 单飞续期 + humanizeError + 纯函数路由守卫纪律。
**后端改动**(P0-A/C):auth-platform-admin 加 resource-server + 方法级授权 + webhook HMAC + 新读端点;auth-platform-core/protocol 给 AuthzEngine 加 readSchema/readRelationships/expand + lookup limit。

---

## 实施步骤(按依赖排序)

- **M0 后端前置**:P0-A(admin 加固)+ P0-B(Casdoor 建 app)。绿了才动前端联调。P0-C(读端点)可与 M2+ 并行。
- **M1 脚手架**:Vite+React+TS+antd 起项目、目录骨架、config 单一出口、vite proxy、路由表 + AppLayout 空壳 + lexicon。
- **M2 鉴权流**:照抄 his-web 五件套改 Casdoor(authority/client_id/profileToSession claim 映射)+ 401 单飞续期 + 登录/回调/silent-renew/登出闭环。
- **M3 数据层 + 原子组件**:api/client(Bearer/401/humanizeError)+ React Query hooks + ResourceRefInput/SubjectRefInput/RelationSelect/PermissionSelect/RefBadge/lexicon 驱动。
- **M4 核心页**:④授予管理(GrantForm userset+元组预览)+ ⑤调试器(check/lookup + 路径降级)。这两页是核心价值,先落。
- **M5 其余页**:①概览 ②schema 查看器(zedParser + 类型卡 + 展开树)③空间管理 ⑥身份同步 ⑦审计(按 P0-C 就绪度决定实现/占位)。
- **M6 交付**:Dockerfile/nginx(8202 反代)+ compose 接入 + build-arg 烘焙。

---

## 测试策略

- **单元/组件**:Vitest + React Testing Library。纯函数直测:zedParser(用真实 knowledge.zed 快照 + 畸形输入降级不崩)、guard(路由裁决)、refFormat、relation/permission 映射。
- **API 层**:MSW 拦 /admin/* 与 Casdoor 端点(比 stub fetch 更贴契约)。
- **鉴权层**:UserManager 包在 auth store 后,注入假会话(未验签 JWT fixture 解 payload)、fake timers 断言续期调度、401 单飞(并发 N 路只刷新一次)。
- **核心不变量**:授予→反查一致(grant viewer→lookupSubjects 命中→revoke→消失)——组件层 MSW 复现;可选**真实 SpiceDB 集成测试**(仿 spicedb-smoke.sh 的服务没起就 skip)钉死。
- **E2E**:一条 gated 的真实 Casdoor OIDC happy-path 冒烟(Playwright,CI 可选),其余用注入会话 + MSW。

---

## 验收标准

- 走 Casdoor SSO 登录成功、受保护页拦截、深链还原、登出(含 end-session)。
- ④授予:给 group 授 editor 自动带 #member,元组预览正确,提交后就地反查命中。
- ⑤调试器:allow/deny 大卡正确;lookupResources/Subjects 反查一致;有 expand 时展示路径,无则降级标注。
- 关闭 authz.casdoor.enabled 时⑥显式提示未启用(409)。
- admin 加固后:未带 token/非 admin 调写端点被 401/403(前端 gate 只是体验,后端是边界)。
- 大结果集不卡死(截断提示 + 虚拟滚动)。

---

## 风险与回滚

- **R0(已列 P0)**:admin 零鉴权/webhook 无验签——M0 必须先加固。
- **R1**:跨站 iframe silent renew 被三方 cookie 拦→切 refresh_token(D5 开关)。
- **R2**:issuer 一致性——不反代 Casdoor,authority 直连真实 :8000。
- **R3**:schema 解析误导——解析失败降级原文 + 标注;可视化只做静态结构,判定路径依赖 expand。
- **R4**:8201 契约漂移——api/* 按真实契约调整,缺失端点走降级分支。
- **回滚(强)**:auth-console 独立新前端,回滚 = 不部署/摘入口;已验证后端(server:8200/knowledge Phase2/161 测试)完全不受影响;前端叠 VITE_ kill switch 改 env 重构建即退。后端 P0 改动只落 admin(:8201)与 Casdoor,不触 server:8200/sdk/core/knowledge-service。

---

## 待澄清问题(不臆造,需你拍板)

1. 本期是否**仅平台/安全管理员**(后端无租户 scope 隔离)?租户自助授权后置?
2. 对象 id 输入:让管理员直接输原始 SpiceDB id(含 `<tenant>_` 前缀),还是要"按业务名解析"(依赖 P0-C directory)?本期默认直接输原始 id。
3. Playground"为何"深度:只回 allowed(现状可做)够不够,还是必须还原继承链(需 expand P0-C)?决定⑤验收标准。
4. 审计是否本期必交(需后端先接 platform-audit)?否则⑦占位。
5. 是否开放"设为公开链接"(写 public_viewer←user:*)?影响面大,需二次确认。
6. token 存储 sessionStorage vs 纯内存(D4)——安全等级由你定。
