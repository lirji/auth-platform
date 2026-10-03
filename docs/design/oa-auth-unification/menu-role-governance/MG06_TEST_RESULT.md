# MG06 菜单来源与发布漂移核对验证

状态：PASS，MG06 DONE；MG00–MG19完整目标仍ACTIVE。下一片MG07，未生产部署、未对原Commerce目录或业务Grant写入。

## 实现与边界

- 当前HUMAN Owner只读核对固定Candidate和本应用发布；完整差异保留实际版本，按未发布、内容／版本、展示、来源未知或不一致、声明一致的顺序判断。读取后重验Owner和版本，竞争409要求重新核对。
- 受信部署声明来自私密服务配置，目标唯一、版本／双摘要／固定提交／制品摘要／时点／证据引用严格校验；请求不能登记runtime证明或任意URL。部署声明与当前发布单独比较。
- 运行制品初版始终UNKNOWN，没有受信实测入口时不把Owner输入或部署标签冒充运行一致。旧无来源历史保持UNKNOWN；旧展示摘要从不可变内容计算，不回填来源／发布历史。
- Console独立核对面板展示来源、发布事实、核对时点、部署声明和完整差异；编辑取消并清除旧结果，403／409／503和UNKNOWN分开。维护CLI及本地人工工具使用原Owner身份和固定本地输入，输出私密报告，不是CI机器身份。
- 没有新增数据库表、定时器、结果自动回退或授权写入口。D-ENV已明确：测试／生产使用独立权限实例及数据库；选项不授予生产部署。

## 验证证据

| 验收 | 方法 | 结果 |
|---|---|---|
| 编译与状态／配置规则 | Admin reactor 208单测（20＋27＋107＋54），含CatalogDriftTest2；强制重新打包当前JAR | PASS |
| PostgreSQL行为 | 专用数据库19项：Drift4／Guard10／Release5；未发布、版本／内容／展示／来源、旧历史、当前Owner和损坏快照依赖失败 | PASS |
| 只读事实保留 | 11类表row_to_json逐行前后相同：目录指针、固定Manifest、展示、来源历史、审计、预览、策略、角色、Grant、委派、范围 | PASS |
| 前端与工具 | Console35 Node测试和build／类型；Python人工核对工具3测试、Python编译和Node语法 | PASS |
| 真实认证及HTTP／CLI | 实际Casdoor PKCE、当前Admin嵌套治理JAR字节核对、随机隔离应用，18项检查；只读CLI报告、401／403、拒绝伪造URL／主体 | PASS |
| 页面交互 | 实际CatalogEditor组件壳5组；来源匹配／旧部署／运行未知、旧版本差异、仅展示变化、未知来源、拒绝／故障清旧结果，管理写请求0 | PASS |
| 视觉 | 1440／390／320；最终15张capture，9张同产品等价状态在前轮实看＋最新6张差异前后详情实看，内边距／长文本换行／底部操作可用 | PASS |
| 卫生与diff | mg06-hygiene-first终态IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，无BLOCKING；命名常量advisory已人工核对为契约上限／工具超时 | PASS（工具限制如下） |

当前非文档代码指纹 `bf3c69cdd23b557ca0e20071ea5b28924f13046f622ae35919df3231dac02fbb`，基线973c94f；逐文件记录为忽略私密mg06-evidence.json。日志：mg06-backend-final.log、mg06-frontend-tests.log、mg06-ui-reviewed-build.log、mg06-tool-tests.log、mg06-runtime-final.log。最终真实HTTP／组件轮次mg06-b54cf765286d；9等价图片来源mg06-9ed9f598e094，差异前后6图在最新轮查看。不是完整门户导航演练。

限制：仓库没有统一formatter／静态分析器，周边风格与卫生检查通过，不声称执行不存在的工具。运行状态UNKNOWN是正式边界；MG11／MG19另核对真实制品。没有容量或P95承诺。

## 失败与恢复

- 首轮PG夹具把Grant授给管理者自身，被既有防自授权规则拒绝。改为同租户独立成员，仅修复夹具，没有放宽业务规则；最终19真PG通过，失败日志保留。
- 首轮差异详情内边距不足，新增局部padding并重新build及实际HTTP／UI，三宽度差异前后6图复查通过。
- 演练只发布随机隔离应用作为输入夹具；原Commerce目录、业务Grant写入0。核对前后授权和目录逐行不变。自有Admin／Vite进程已退出，私密证据和原服务保留。
- 回退仅停止诊断工具；不删除历史、不自动回退目录或授权，不改已执行V21／V22。MG05提交973c94f精确CI37105196879 SUCCESS，MG06 Git／CI收口由PROGRESS_STATE记录。
