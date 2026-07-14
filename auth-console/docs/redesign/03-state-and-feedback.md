# 03 · 状态与反馈

加载 / 错误 / 空 / 成功四态的统一原语与使用范式。目标:每页每个数据区都有明确、一致的状态呈现,
杜绝「请求中一片空白」「报错静默」「空态与未开始混淆」。

## 涉及文件

| 文件 | 导出 | 用途 |
|---|---|---|
| `src/components/common/AsyncState.tsx` | `PageSkeleton` / `ErrorState` / `EmptyState` | 三个状态原语 |
| `src/hooks/useAuthz.ts`(未变) | `humanizeError` | 把异常转成中文可读文案,喂给 `ErrorState` |

## 三个原语(`AsyncState.tsx`)

```tsx
// 首次加载骨架
<PageSkeleton rows={4} />                      // 默认 rows=6

// 错误 + 重试(status="warning" 的 Result)
<ErrorState message={humanizeError(err)} onRetry={() => q.refetch()} />

// 空态(与 idle 文案区分;可挂操作按钮)
<EmptyState description="未解析出类型定义…" extra={<Button …/>} />
```

- `PageSkeleton` = `Skeleton active`;`ErrorState` = `Result` + 「重试」按钮(`onRetry` 可选);
  `EmptyState` = `Empty`,`extra` 作为 children 挂按钮(如「查看原始 .zed」)。

## 状态范式:idle / loading / error / data 四分支

**关键教训(codex-review 修出的 bug)**:多模式页面(如 Playground 有 check/反查资源/反查主体三模式)
**不能共用一个全局 idle 判断**——否则「A 模式已有数据 → 切到 B 模式」会错误显示 A 的结果或空白。
正确做法是**每个数据区独立跑满四分支**:

```tsx
{mode === 'check' && (
  check.isPending ? <PageSkeleton rows={4}/>
  : check.isError ? <ErrorState message={humanizeError(check.error)} onRetry={run}/>
  : check.data    ? <>…结果…</>
  :                 <EmptyState description="填好主体+权限+资源,点运行查看判定"/>
)}
```

- **loading** 用 `isPending`(mutation)/ `isLoading`(query,**首次**,不用 `isFetching` 以免后台刷新遮住旧数据)。
- **error** 一律走 `ErrorState`,**不再叠加 toast**(此前 `onError` toast + Result 双重反馈已移除,避免重复打扰)。
- **data** vs **idle(EmptyState)** 必须区分:`data ? … : <EmptyState/>`,空数组也算「查询完成无匹配」(用 `List` 的 `locale.emptyText`),与「尚未运行」不同。

## 各处落点

| 页面 / 组件 | loading | error | empty / idle |
|---|---|---|---|
| Playground(三模式各自独立) | `PageSkeleton` | `ErrorState` + 重试 | `EmptyState`「点运行…」 |
| Playground expand 子卡 | `Spin` | `Alert warning`「路径加载失败(不影响判定)」 | `Text`「无」 |
| Grants 现存授予列表 | `List loading`(首次) | `ErrorState` + 重试 | `List.locale`「填入资源 id 后自动加载」 |
| SchemaViewer | `PageSkeleton` | `ErrorState` | `EmptyState`「未解析出类型」+ 转原始 |
| Audit | `Table loading` | `ErrorState` | `Table.locale`「暂无审计记录」 |
| IdentitySync | 按钮 `loading` | `Result warning` | `sync.isSuccess` 后出统计 |

## 成功反馈

- 写操作(授予/撤销/同步)成功用 `message.success`(antd `App.useApp()`),附 ZedToken 前缀便于核对。
- 只读查询无需成功 toast,直接渲染数据即成功信号。
