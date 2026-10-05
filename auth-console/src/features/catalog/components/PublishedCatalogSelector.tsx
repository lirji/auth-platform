import { LifecycleState } from '../../governance/shared/codes.ts';
import { menuTitle, orderedMenus } from '../model/catalogModel';
import { useState } from 'react';
import { Alert, Button, Checkbox, Input, Select, Space, Tag, TreeSelect, Typography } from 'antd';
import type { PublishedCatalog } from '../../../api/governance';
import { menuCapabilityCodes } from '../model/publishedCatalog';

/** 已发布目录只作浏览上下文；父节点和菜单切换都不会选择或删除能力。 */
export function PublishedCatalogBrowser({ catalog }: { catalog: PublishedCatalog }) {
  return (
    <div className="g-catalog-summary">
      <Typography.Text strong>当前已发布目录 · v{catalog.manifest_version}</Typography.Text>
      <Typography.Text type="secondary">
        {catalog.menus.length} 个菜单 · {catalog.capabilities.length} 项能力 ·{' '}
        {catalog.resource_types.length} 种资源
      </Typography.Text>
      <Typography.Text className="mono" copyable={{ text: catalog.content_hash }}>
        内容摘要 {catalog.content_hash.slice(0, 12)}
      </Typography.Text>
      {!catalog.menus.length && (
        <Alert
          type="info"
          showIcon
          message="当前版本尚未发布菜单"
          description="资源与能力来自真实已发布清单；菜单需要应用Owner发布准确映射。"
        />
      )}
    </div>
  );
}

export function PublishedCatalogSelector({
  catalog,
  resource,
  value = [],
  onChange,
  disabled = false,
}: {
  catalog: PublishedCatalog;
  resource?: string;
  value?: string[];
  onChange?: (codes: string[]) => void;
  disabled?: boolean;
}) {
  const [search, setSearch] = useState('');
  const [menu, setMenu] = useState<string>();
  const [unlinked, setUnlinked] = useState(false);
  const codes = menuCapabilityCodes(catalog, menu);
  const linked = new Set(catalog.menus.flatMap((menu) => menu.any_of));
  const query = search.trim().toLowerCase();
  const shown = catalog.capabilities.filter(
    (cap) =>
      (!resource || cap.resource_type === resource) &&
      (!codes || codes.has(cap.code)) &&
      (!unlinked || !linked.has(cap.code)) &&
      (!query || cap.code.includes(query) || cap.resource_type.includes(query)),
  );
  const stale = value.filter(
    (code) =>
      !catalog.capabilities.some(
        (cap) => cap.code === code && cap.resource_type === resource && cap.grantable,
      ),
  );
  const toggle = (code: string, checked: boolean) =>
    onChange?.(checked ? [...value, code] : value.filter((current) => current !== code));
  return (
    <div className="g-catalog-selector">
      <Space direction="vertical" style={{ width: '100%' }}>
        <Input.Search
          aria-label="搜索当前能力"
          placeholder="搜索能力或资源编码"
          value={search}
          onChange={(event) => setSearch(event.target.value.toLowerCase())}
          disabled={disabled}
        />
        <TreeSelect
          aria-label="菜单浏览上下文"
          placeholder="全部菜单及未关联能力"
          allowClear
          disabled={disabled || !catalog.menus.length}
          style={{ width: '100%' }}
          value={menu}
          onChange={(value) => {
            setMenu(value);
            setUnlinked(false);
          }}
          treeDefaultExpandAll
          treeData={orderedMenus(catalog).map((menu) => ({
            id: menu.code,
            pId: menu.parent ?? undefined,
            value: menu.code,
            title: `${menuTitle(menu)}${menu.route ? ` · ${menu.route}` : ''}`,
          }))}
          treeDataSimpleMode
        />
        <Select
          aria-label="能力关联筛选"
          value={unlinked ? 'unlinked' : 'all'}
          disabled={disabled}
          options={[
            { value: 'all', label: '显示当前浏览能力' },
            { value: 'unlinked', label: '仅未关联菜单能力' },
          ]}
          onChange={(value) => {
            setUnlinked(value === 'unlinked');
            if (value === 'unlinked') setMenu(undefined);
          }}
          style={{ width: '100%' }}
        />
      </Space>
      <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
        菜单仅用于查找。已明确选择 {value.length} 项能力；搜索与菜单切换保留选择。
      </Typography.Paragraph>
      {!!stale.length && (
        <Alert
          type="warning"
          message="保留的旧选择需要明确纠正"
          description={
            <>
              {stale.map((code) => (
                <Tag key={code} style={{ whiteSpace: 'normal', overflowWrap: 'anywhere' }}>
                  {code}
                </Tag>
              ))}
              <Button
                disabled={disabled}
                onClick={() => onChange?.(value.filter((code) => !stale.includes(code)))}
              >
                移除不兼容选择
              </Button>
            </>
          }
        />
      )}
      <div className="g-catalog-capabilities">
        {shown.map((cap) => (
          <div className="g-catalog-capability" key={cap.code}>
            <Checkbox
              checked={value.includes(cap.code)}
              disabled={disabled || !resource || (!cap.grantable && !value.includes(cap.code))}
              onChange={(event) => toggle(cap.code, event.target.checked)}
            >
              <span className="mono">{cap.code}</span>
            </Checkbox>
            <Space wrap size={4}>
              <Tag>{cap.resource_type}</Tag>
              <Tag color={cap.risk_level === 'HIGH' ? 'warning' : undefined}>{cap.risk_level}</Tag>
              {cap.disabled && <Tag color="error">已停用</Tag>}
              {cap.lifecycle_state === LifecycleState.RETIRED ? (
                <Tag>已最终退役</Tag>
              ) : cap.lifecycle_state === LifecycleState.DEPRECATED ? (
                <Tag color="warning">已弃用 · 停止新增</Tag>
              ) : (
                !cap.disabled && !cap.grantable && <Tag>超当前委派</Tag>
              )}
            </Space>
          </div>
        ))}
        {!shown.length && (
          <Typography.Paragraph type="secondary">
            当前筛选没有能力，请调整菜单或搜索。
          </Typography.Paragraph>
        )}
      </div>
    </div>
  );
}
