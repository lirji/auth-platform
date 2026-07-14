# 04 · 领域组件

权限领域专属的展示组件。核心是 **relation(可授予)与 permission(可判定)的视觉区分——不只靠颜色**。

## 涉及文件

| 文件 | 导出 | 职责 |
|---|---|---|
| `src/components/domain/SemanticTag.tsx` | `RelationTag` / `PermissionTag` | relation vs permission 语义标签 |
| `src/components/domain/AllowDenyResult.tsx` | `AllowDenyResult` | 判定结果大卡(ALLOW/DENY) |
| `src/components/domain/SchemaTypeCard.tsx` | `SchemaTypeCard` | 单个 `.zed` 类型卡片(关系区 + 权限区) |
| `src/components/domain/RefBadge.tsx` | `RefBadge` / `TupleText` | 对象引用徽章 / 关系元组等宽文本 |
| `src/components/domain/selects.tsx` | `ObjectTypeSelect` / `RelationSelect` / `PermissionSelect` | 三种领域下拉(数据源自 `lexicon`) |

> 所有组件都是**纯展示**:只接 props,不调 API、不含业务判断。颜色/标签取自未改的 `domain/lexicon.ts`。

## relation vs permission 区分(`SemanticTag.tsx`)

SpiceDB 里 **relation(可授予,写元组用)** 与 **permission(可判定,check 用)** 概念完全不同,
UI 必须让管理员一眼分清。**不依赖颜色**(色盲友好),用**边框样式 + 文字前缀 + Tooltip** 三重区分:

| | 关系 relation | 权限 permission |
|---|---|---|
| 样式 | **虚线描边** `borderStyle: 'dashed'` | **实心填充** `bordered={false}` |
| 前缀 | `关系·<label>` | `权限·<label>` |
| Tooltip | 「关系(可授予)」 | 「权限(可判定)」 |
| 颜色 | `RELATIONS[name].color`(锦上添花) | `PERMISSIONS[name].color` |

```tsx
<RelationTag name="editor" />      // 虚线 · 关系·编辑者
<PermissionTag name="view" />      // 实心 · 权限·查看
```

## 判定结果大卡(`AllowDenyResult`)

Playground check 的主结果。只接 `allowed: boolean`,不调 check:

```tsx
<AllowDenyResult allowed={check.data.allowed} />
// true  → Result success + CheckCircleFilled(colors.success) 「允许 · ALLOW」
// false → Result error   + CloseCircleFilled(colors.error)   「拒绝 · DENY」
```

## 类型卡片(`SchemaTypeCard`)

授权模型页把解析后的 `.zed`(`ParsedDefinition`)渲染成卡片,**关系区在上、权限区在下**,中间 `Divider`:

- 标题:类型 `Tag`(色取自 `OBJECT_TYPES`)+ 中文名(`objectLabel`)。
- 关系区:每条 `RelationTag` + `← 可授予的主体类型`(`subjectTypes.join(' | ')`)。
- 权限区:每条 `PermissionTag` + 等宽 `= <推导表达式>`(如 `= viewer + comment + parent_space->view`)。
- `body.minHeight: 120`,网格里各卡高度更整齐。

## 引用与元组(`RefBadge.tsx`)

- **`RefBadge`**:`<type>:<id>[#relation]` 徽章,等宽字体,色取自 `OBJECT_TYPES`;
  `maxWidth:100% + wordBreak:break-all + whiteSpace:normal`——长 id **换行不撑破**布局。
- **`TupleText`**:整条关系元组 `resource#relation@subject` 的等宽渲染(`.mono`)。

## 领域下拉(`selects.tsx`)

三个下拉,选项分别来自 `lexicon` 的 `OBJECT_TYPES` / `relationsFor(type)` / `permissionsFor(type)`:

- `ObjectTypeSelect`:对象类型,支持 `exclude` 过滤(如授予表单排除 `user` 作资源)。
- `RelationSelect`:随 `resourceType` 联动可授予关系;无关系时 `notFoundContent` 提示。
- `PermissionSelect`:随 `resourceType` 联动可判定权限。

> **无障碍新增**:三者都加了可选 `id?` 形参,透传到内层 antd `<Select id>`,
> 供页面用 `<label htmlFor>` 关联(详见 [06 · 无障碍](./06-accessibility.md))。不传 `id` 时行为与旧版一致。
