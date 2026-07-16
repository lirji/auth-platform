# 项目技术栈文档

本目录整理 **auth-platform（内部统一权限平台）** 实际用到的数据库与组件，内容全部来自仓库现有代码与配置，
非规划态。规划态但尚未落地的部分会显式标注。

> 定位：纯授权平台。**Casdoor 管身份/登录/SSO（OIDC），SpiceDB 管 Zanzibar/ReBAC 细粒度授权**。
> 本仓库不含登录代码、不含 LLM 集成。Maven 多模块，Spring Boot 3.3.5 / Java 21。

## 文档索引

| 文档 | 内容 |
|---|---|
| [getting-started-authz.md](./getting-started-authz.md) | **新手上手**：如何建立用户/组织/权限关系（ReBAC 换脑子 + 手把手 grant 示例 + 验证） |
| [databases.md](./databases.md) | 数据库与存储：PostgreSQL、SpiceDB（关系元组存储）、数据模型（`.zed`）、一致性水位 |
| [components.md](./components.md) | 组件清单：基础设施/中间件、后端 Java/Spring 模块与依赖、前端 React 依赖 |

> 授权模型与接入的权威文档在仓库根 `docs/`：**[平台能力总览](../docs/平台能力总览.md)**（能力矩阵 + API 参考 + 边界）、
> **[新项目接入指南](../docs/新项目接入指南.md)**（接入项目侧/平台侧双清单）、
> **[授权：部门层级知识隔离模型](../docs/authz-department-model.md)**（当前 `document` 模型，取代旧 D3）、
> **[Casdoor 统一登录平台接入手册](../docs/统一登录平台接入手册.md)**（SSO/OIDC 接入 + 多租户开通）、
> **[性能与容量规划](../docs/性能与容量规划.md)**（高并发承载/瓶颈/扩容路线）。

## 全栈一览

| 层 | 组件 | 版本/镜像 | 角色 |
|---|---|---|---|
| 身份 | Casdoor | `casbin/casdoor:latest` | 登录 / SSO / OIDC 发行方（IdP） |
| 授权 | SpiceDB | `authzed/spicedb:latest` | Zanzibar/ReBAC 判权引擎 + 关系元组存储 |
| 数据库 | PostgreSQL | `postgres:16` | SpiceDB 与 Casdoor 共用的关系库 |
| 后端 | Spring Boot | 3.3.5 / Java 21 | server(:8200) + admin(:8201) 两个服务 + 三个库模块 |
| 前端 | React + Vite | React 18 / Vite 5 | `auth-console` 管控台（授权侧） |
| 静态托管 | nginx | `nginx:1.27-alpine` | 前端产物托管 + 同源反代 `/admin` |

## 端口速查（宿主机侧，刻意避开常见占用）

| 端口 | 服务 | 说明 |
|---|---|---|
| 8000 | Casdoor | 身份/OIDC，issuer=`http://localhost:8000` |
| 50051 | SpiceDB gRPC | 容器 50051（本平台**不使用** gRPC，见备注） |
| 8543 | SpiceDB HTTP | 容器 8443；判权走 HTTP/JSON |
| 9099 | SpiceDB metrics | 容器 9090，Prometheus |
| 15432 | PostgreSQL | 容器 5432 |
| 8200 | auth-platform-server | 判权 REST facade |
| 8201 | auth-platform-admin | 授权管理 API / auth-console 后端 |
| 8202 | auth-console (nginx) | 生产前端托管 |
| 5273 | auth-console (vite dev) | 开发前端 |

> **grpc-free 约束**：全程用 SpiceDB 的 HTTP/JSON API（Spring `RestClient`），刻意避开 grpc/protobuf
> 依赖冲突。50051 端口暴露仅为兼容，代码不走 gRPC。

## 依赖方向

```
protocol ← core ← { server, admin }
protocol ← sdk
```

`auth-platform-protocol`（纯 Java 契约层）是所有依赖的汇聚点，换判权引擎只需新增一个 `AuthzEngine` 实现。
