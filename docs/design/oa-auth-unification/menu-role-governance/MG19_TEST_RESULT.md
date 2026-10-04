# MG19 定向恢复验收（PASS）

2026-10-03。MG19必要验证及Git／精确CI DONE，产品d283ad7171ff8ff224517b2e7818e86542741576正常ff合并／推main，精确[CI37173735107](https://github.com/lirji/auth-platform/actions/runs/37173735107) completed SUCCESS、head完全一致，详见[PROGRESS_STATE](PROGRESS_STATE.md)。2工具／CI源码路径指纹 `1eb333ecd497c6dcc7fc85c6b5ba046a31e360956e60e1da5c90098bc013be27`，验收和CI后逐路径SHA核对一致；Admin使用已验证MG18制品b345095e95897d6650ada2341c8f81dc8922fb45f37038bc18d3ec1e44e6bdd7，不宣称已部署原运行环境。

| 冻结验收 | 实際证据与结果 |
|---|---|
| IdP依赖断连／恢复 | mg19-recovery-third.log actual exit0；mg19-recovery-2c61683caae8/result.json与proxy-records.json：实际自有JVM固定issuer，经允许列表代理转发真实18094。版本断TCP2次／introspection1次／空公钥缓存新JVM的JWKS2次，HTTP503 DEPENDENCY_UNAVAILABLE；各恢复后实际IdP再认证成功。代理未伪造HTTP503或有效Token，不停止IdP |
| 图断连／恢复 | 同轮真实/me/access原三ACTIVE来源有read提示，实际图TCP断1次接口503，恢复实际18544后同一能力可用；没有重授权／缓存ALLOW |
| 更高目录修正 | 同轮自有应用v2→v3真实GUARDED预览与提交，旧v1／v2Manifest行前后相同；低版新预览409，原v2命令重放返回原回执、指针仍v3。独立correction-display-proof.json实际SQL核对v2“待修正名称”／v3“修正后的目录名称”、不同展示摘要和对应release一致，旧v2展示仍保留 |
| 发布结果未知／实际进程恢复 | 实际v2 API提交成功200后独占回环代理断响应，调用方RemoteDisconnected，原命令／候选固定私密UNKNOWN检查点；实际旧Admin PID2448退出，新PID2466重开、同原命令取得完全相同回执。原command的catalog_release始终恰好1条，不换键 |
| 基线与权限 | 同轮22HTTP＋1SQL保护=23检查PASS；角色／4Grant／委派／复核任务与条目全部行前后相同（3ACTIVE、1原REVOKED）；普通403、匿名401、跨租户MEMBERSHIP_UNAVAILABLE403仍拒绝。原业务Grant写入0，自有Grant写入0，无原Commerce发布／5273更新 |
| 两真实JVM投影恢复 | mg19-process-first.log actual exit0／BUILD SUCCESS，当前ProjectionProcessIT2项真PG＋图PASS。旧PID2291 SIGSTOP、新PID2356撤权完成后，旧写恢复实际远端400／code9不能恢复已撤源；图真实提交后PID2221 SIGKILL exit137、SQL回执0，租约自然等待14846ms，新PID2287实际marker恢复，operation／receipt各1。不是RTO实测承诺 |
| 必要回归 | 上述reactor202单测PASS（protocol20／core27／governance155），不含Admin54且不合成新的256。当前产品未变化；MG18最新256后端／10PG／2图／50前端与构建、20HTTP／5浏览器组／18图及实际复核Admin重启证据仍有效。MG13／14按冻结契约的固定范围、真实先撤后授、Runtime重开／断连、组与新OA批准证据保留复用，不声称本轮重跑 |
| 界面一致性 | 本片不修改页面，视觉增量N/A。当前MG18 18实看图／5实际组证明503旧状态与写动作隐藏／恢复、实际普通403；MG16终态12图／6组保留，当前HTTP拒绝一致，不用响应夹具替代本轮实际依赖断连 |
| 工具／文档／资源 | Python语法、CI两触发路径／语法命令、文档链接／秘密模式／diff PASS。自有21830和实际Admin／代理进程退出，所有证据及失败保留；准确运行手册区分程序回退／目录修正／授权补偿／隔离数据恢复 |

## 卫生与失败

mg19-hygiene-first.json：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，0 BLOCKING／1 ADVISORY。HF001为工具明确命名21830端口、10秒IO、10MiB响应上限；只允许两回环依赖，子进程环境中的代理设置恢复，不修改用户／共享运行环境。FORMAT SKIPPED／TOOL_NOT_AVAILABLE，沿周边风格；STATIC_ANALYSIS NOT_APPLICABLE；无产品事务改动。

第一轮跨租户实际403是MEMBERSHIP_UNAVAILABLE，工具误预期ACCESS_DENIED；第二轮把目录当前版本1当作GUARDED初始化版本0，实际400正确拒绝。两轮没有目录成功写入，失败／检查记录保留；工具按原契约修正，第三轮完整执行PASS，未放宽产品权限、修改原数据或迁移。

真实OA引擎未联调；生产运行核验器未接入时UNPROVEN仍阻止正常退役，运行漂移UNKNOWN仍显示未知；机器自动发布默认关闭。未生产部署、容量／RTO／RPO评估、共享服务重启、数据恢复或清理，不写未测达标。
