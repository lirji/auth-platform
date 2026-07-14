# 06 · 无障碍(a11y)

键盘可达、语义标签、焦点管理。多数为 codex-review 后修复项与后置项收尾。

## 键盘可达

| 元素 | 处理 | 文件 |
|---|---|---|
| Overview 任务卡 | `role="button"` + `tabIndex={0}` + `onKeyDown`(Enter/Space → 跳转,`preventDefault`) | `OverviewPage.tsx` |
| Header 折叠/汉堡按钮 | 换成 antd `Button`(原生可聚焦) | `AppLayout.tsx` |
| 账户下拉触发器 | `Dropdown` 触发器用可聚焦 `Button` 包裹(非裸 `<span>`) | `AppLayout.tsx` |

```tsx
// TaskCard:非原生可点元素补键盘语义
<Card role="button" tabIndex={0} onClick={go}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go() } }}>
```

## aria-label

按钮/开关无可见文字或语义随状态变时,补 `aria-label`:

| 元素 | aria-label |
|---|---|
| Header 汉堡/折叠按钮 | `打开菜单` / `展开菜单` / `收起菜单`(随 `isMobile`/`collapsed` 变) |
| `#member` Switch(Grants) | `作为集合成员 #member` |
| 附属 Input(资源 id / 主体 id) | `资源 id` / `主体 id`(placeholder 不算无障碍名) |

## 表单 label 关联(`<label htmlFor>`)

把 Grants / Playground 表单的 `Typography.Text strong` **伪标签**换成真 `<label htmlFor>`,
关联到主控件——屏幕阅读器可读出字段名,点标签也能聚焦控件。

**前置改动**:`selects.tsx` 三个 Select 加可选 `id?`,透传到内层 antd `<Select id>`(antd 会把 id 落到内部输入)。

```tsx
// selects.tsx
export function ObjectTypeSelect({ value, onChange, exclude, id }: { …; id?: string }) {
  return <Select id={id} … />
}

// GrantsPage.tsx / PlaygroundPage.tsx
<label htmlFor="grant-res-type" style={{ fontWeight: 600 }}>资源</label>
<ObjectTypeSelect id="grant-res-type" … />
```

关联的 id 清单:

| 页面 | 字段 | id |
|---|---|---|
| Grants | 资源类型 / 关系 / 主体类型 | `grant-res-type` / `grant-relation` / `grant-subj-type` |
| Playground | 主体类型 / 资源类型 / 权限 | `pg-subj-type` / `pg-res-type` / `pg-perm` |

> 每个字段的 `<label>` 关联该字段组的**主控件**(类型 Select);同组附属 Input 用 `aria-label` 单独命名。

## 焦点管理(移动端 Drawer)

移动端 `Drawer` 的**焦点陷阱、`Esc` 关闭、遮罩、打开时焦点移入**全部由 antd `Drawer` 内建,
无需手写。点菜单项跳转后 `afterClick` 关闭 Drawer,焦点回到触发区。详见 [02](./02-layout-and-navigation.md)。

## 颜色不作为唯一信息通道

relation vs permission 除颜色外用**边框样式 + 文字前缀 + Tooltip** 区分(见 [04](./04-domain-components.md)),
ALLOW/DENY 除红绿外有图标 + 「允许/拒绝」文字,对色觉障碍用户友好。

## 已知后续项(非本次范围)

- 未做自动化 a11y 测试(axe / jest-axe)——本次靠手测 + 结构规范保证。
- 深浅色主题仅浅色;如需深色再扩 `theme.ts`。
