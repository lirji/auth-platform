# P1-05 TEST_RESULT

Gate PASS（本片隔离验收）。源码指纹 `77c820cc64bc1fb5e541edcec21ce682b23710efc9c6b9c50514613a67510d93`，逐文件摘要见 P1-05-evidence-index.json。

181 单元测试、29 PostgreSQL 集成测试（含新增 9 项邀请用例）、5 真实 Casdoor 测试、
30 邀请 HTTP/CLI 检查、30 既有上下文/停用 HTTP 检查全部通过，零失败/错误/跳过。
已执行 V1–V3 未修改；V4 迁移及旧 P1-03 JAR 读取兼容通过，旧读取仍排除停用负责人。
逐项验收与方法见 P1-05-test-results.json。

独立核查事务边界：外部网络验证发生在事务之前；邀请、身份、成员、命令和审计共用同一治理数据源。
真实并发证明同邀请只产生一次接受结果；真实审计触发器故障证明主体/绑定/成员/邀请一起回滚。
当前负责人/主体锁与成员条件更新防止用历史登录状态绕过停用。
不调用角色、应用或图写入能力，接受响应不包含业务 ALLOW。
通用治理前缀仍核对绑定；只有明确 POST 邀请路径使用显式证明完成首次绑定。

Hygiene：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，仓库没有统一 formatter；
独立静态分析器 N/A。配置、令牌与原始异常不入日志，CLI 边界统一非零脱敏退出。
本片无 UI、无生产容量承诺、无生产部署；完整 IdP 升级的 redirect_uri 缺陷仍 HOLD。
远程精确提交 CI 待验证，不将本地 PASS 冒充远程门禁通过。


CI 第一次运行 36392202962 在新增邀请进程重启时 OSError；其余步骤 PASS。
隔离 Linux 容器复现原端口探测在 TIME_WAIT 下 errno=98，SO_REUSEADDR 修复后 PASS，
活动监听仍被拒绝。没有使用 SO_REUSEPORT、杀他人进程或降低产品认证断言。
本地重跑 30 项邀请检查 PASS；重复夹具已绑定时的关闭路由兼容旧 /error 链返回 401。
源码修复仅测试运行工具，181 单测/29 PG/5 IdP 的产品源码未变。远程修复 SHA CI 待执行。


最终远程门禁 PASS：`47ff9d9fac439d8fad9b38f48687868b8e41b6de`，
[CI 36392809729](https://github.com/lirji/auth-platform/actions/runs/36392809729) SUCCESS。
新增邀请链路和既有治理回归均通过；第一次失败及修复证据仍保留。
后续 P1-04 契约提交 c9e33ce 仅改设计文档，不改变本片已验证产品源码。
