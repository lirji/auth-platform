# W07 真实本机验收

2026-10-05 UTC，切片实现及本机验证 PASS_WITH_LIMITATIONS；生产、容量/可用性/RTO/RPO及TCC业务未在本片证明。Git/CI使用精确提交独立Gate，不将本地PASS当远端成功。

当前产品源：Auth dd04404/CI37249142702 SUCCESS，WMS 0f62d41/CI37249068115全部Java/console/profile SUCCESS，均正常发布main；W07仅运行/工具/验证/文档/CI增量，没有Java或UI产品源码变更。四源码镜像从这两产品源构建，以私密candidate-images.json的不可变ID实际部署。配置/脚本工作树指纹另由delivery-fence记录。

| 验证 | 实际结果与边界 |
|---|---|
| 当前镜像/HTTPS | 四源码构建PASS；隔离Docker18项可信CA、未知CA/错误主机名拒绝、5独立凭据、UID10001/600、越仓/撤权/故障PASS |
| 实际旧机器发行/Owner/SQL/共存 | 23项PASS；3固定最小scope主体；序列认领/激活/原幂等键重放、来源窗口；旧序列镜像读同一新状态MySQL并重放原键 |
| 真实Auth管理页面 | 角色/WH-A指定范围/390px3项，真实严格撤权1项、完成回执/窄屏角色行2项PASS；仓没有TENANT_ALL选项，SQL/同授予前Token A200→403且B403，既有连续projector完成，没有另跑一次性投影 |
| 原本机WMS页面 | 真实4用户PKCE、6项PASS、0脚本错/0 mock/0 token注入/0业务写，最终0个5xx；仓选择/菜单真实操作，越仓深链直接访问、PDA390px禁止提交、无来源空菜单；5张当前图均实看 |
| 原目标最终故障/恢复 | 11项PASS：5实际Owner读/独立SDK，匿名401、B→A403、无Grant403，当前TLS Auth pause令业务及导航503，同旧Token恢复200；故障响应有界 |
| 原数据与工作树 | 两MySQL镜像/业务卷相同；最终142表行数及摘要一致。两初始化目录挂载路径变化但文件字节相同。原dirty WMS HEAD/status/55保护文件均相同，Driver/ERP/local main未改 |
| 目标就绪 | 原6WMS应用+治理console/admin/projector+WMS relay/server共11服务最终healthy，实际镜像核对；Graph/IdP原实例保留 |
| 工具与配置 | 4新Python编译、初始化bash语法、浏览器Node语法、两Compose解析/diff与5既有工具测试PASS；文档/秘密/范围检查由当前交付检查记录 |

证据仅在忽略的0700/0600 `.local/wms-auth-integration/`：docker/original-target-browser-final、auth-ui、machine-smoke-8cace0a946dc/result.json、original-target-final-fault.json、final-target-verification.json、backups、graph-container-binding.json、w07-original-worktree-guard.json。账号及期限见私密w03-state/ACCESS.md，公开文档不包含实际凭据。

最初原Kafka/Redis停止使消息客户端启动失败，正常恢复既有组件；未重建Topic或启用新worker。共享VM内存回收停顿、宿主Graph转发3秒超时、后端IP变化导致旧Nginx502，以及测试选择器/加载时序/SQL探针错误均保留失败原证据。当前只降低本任务堆预算，绑定同一Graph本体并保留客户端回环，console依赖更新重启；不放宽TLS、2秒身份期限、SQL约束或允许缓存。最终实测不推导一般容量保证。

8条独立演示授权遵循原24小时上限；测试UI来源已严格回收。机器JWT1小时，显式续发工具已记录，没有自动续发。TCC仅发行和内部过滤链证明，原RM/执行关闭；盘点APPLYING1行、调拨物理发2/收2不被本片改称全流程完成。旧序列共存是实际回退兼容证据，未实际全目标回滚、恢复快照或撤销业务效果。详见 [Runtime](RUNTIME_SPEC.md)、[审查](W07_REVIEW.md)。
