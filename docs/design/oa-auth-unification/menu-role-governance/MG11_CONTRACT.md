# MG11：CI目录发布客户端契约 v1

状态：FROZEN（2026-10-03）。复用[MG09_CONTRACT](MG09_CONTRACT.md)、MG10独立机器接口及MG01固定Git声明导出，不新增后端权限、身份提供方、进程或中间件。机器入口和工具发布权限都默认关闭；正常Git推送不自动发布目录或部署生产。

## 固定输入和目标

- Python标准库CLI提供preview（缺省dry-run）、publish和recover三阶段。preview仅请求服务器固定预览并保存准确报告；publish需要原检查点以及目标配置publish_enabled=true。首次或语义变化必须REQUIRES_OWNER_REVIEW，不产生机器发布命令调用。
- 目标来自0600、非符号链接、大小≤8192字节的严格JSON文件：base_url、target_instance_id、environment、application_id、publisher_id、delegation_id、service_principal、issuer、client_id、client_secret；可选publish_enabled（缺省false）和timeout_seconds（缺省5，1至5）。未知／重复／错类型拒绝。UUID／code与MG09一致，base_url和issuer固定origin，无路径、query、fragment或URL凭据；生产HTTPS，回环HTTP仅隔离开发。
- Token仅在内存，通过固定issuer的Casdoor client_credentials取得；不追随HTTP重定向，不从候选或Token声明构造网络地址。Token／secret不写检查点、报告、stdout或异常；HTTP错误只保留稳定code／trace_id。
- preview提供固定预期Git commit、原始声明制品文件及导出候选。commit必须精确40／64位，制品SHA256必须等于source.artifact_hash，候选source.commit必须等于预期提交，manifest除版本外必须与该制品的完整声明一致。声明≤131072、候选≤141312字节；重复／未知字段与宽松标量拒绝。兼容MG01导出的auto_grants／auto_roles字段仅可false，不转发机器API；非空decision拒绝。
- 预期commit由构建阶段固定，原声明制品从同一提交materialize；不读取浮动工作区文件冒充该提交。这里的artifact是原声明字节，不是业务服务镜像或JAR，运行状态不能据此改为已核验。

## 检查点、并发和恢复

- 每次构建使用独立0600检查点（目录0700），锁文件互斥、原子替换并fsync；不自动覆盖其他任务检查点。固定目标／客户端／委派／候选与原文件SHA、准确report、原command_id及preview_id全部保存，无认证凭据。
- preview结果必须精确对应目标、publisher、delegation、应用、候选版本及来源；AUTOMATION_ALLOWED需有效固定ticket与相同规范化候选。机器报告没有全人员影响授权，不补COMPLETE。
- publish前重验目标与输入字节／制品SHA／提交栅栏，再发送原command_id＋preview_id；预览后候选或原制品被替换即拒绝，不重新预览或换键掩盖变化。
- 在发送发布请求前持久化PUBLISHING_UNKNOWN。网络中断、超时、5xx或回执结构错误保留UNKNOWN，不能将不确定结果归为未提交。400／401／403／409等明确拒绝保存稳定状态，不能自动换command或创建新候选。
- recover仅查询当前配置所对应委派的原command；固定checkpoint中已提交请求是恢复依据，不要求当前源码文件仍存在。404仍保持UNKNOWN，允许持原输入同体重试；无当前有效身份／委派则保留未知事实并明确拒绝，不假装未发布。
- 成功回执必须匹配原命令、版本、来源、双摘要、基础版本／双摘要、原preview和实际SERVICE actor／Owner／委派／目标。任何不符不标成功。已PUBLISHED重读仍验证原命令，不使用旧报告作为当前授权。

## 报告和CI边界

报告分别记录directory_publication、projection_status和runtime_status。机器只拥有目录发布资格，不能读取全人员、Grant或授权图管理状态；没有独立可信检查时projection_status=UNKNOWN、runtime_status=UNKNOWN。目录PUBLISHED仅代表固定快照事务成功，不代表业务权限、图投影或部署准备完成。

现有CI增加工具协议回归／语法检查；默认自动任务不获取真实发布凭据、不运行生产发布。CI集成先调用preview并保存报告，明确获授权目标才能调用publish；实际写入演练仅使用新专用测试目标。启用真实远程job需要受控环境配置与凭据，不在本片自动创建GitHub Secret或远程部署环境，也不声称未执行的托管环境发布通过。

必要验收：完整隔离API链路、错目标／环境／版本拒绝、候选与制品替换栅栏、真实已提交响应丢失后原回执恢复、同命令重试不重复发布、严格响应和凭据脱敏、报告准确关联固定提交及原声明SHA。协议fixture与真实IdP／数据库结果分别记录，不用Mock证明事务。
