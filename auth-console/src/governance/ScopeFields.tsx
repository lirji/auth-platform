import { Alert, Form, Input, Select, Typography } from 'antd'
import { ScopeKind } from './codes'
import type { PublishedResourceType, ScopeRule } from '../api/governance'

export interface ScopeInput { resource_type: string; scope_kind: string; scope_values?: string }
const labels: Record<string, string> = { TENANT_ALL: '当前企业全部资源', SPECIFIED_STORES: '指定门店', SPECIFIED_RESOURCES: '指定资源' }
/** 类型及范围来自当前真实目录；未知绑定不能自动退化成全企业。 */
export function ScopeFields({ resourceTypes }: { resourceTypes: PublishedResourceType[] }) {
  const form = Form.useFormInstance()
  const resource = Form.useWatch('resource_type', form)
  const kind = Form.useWatch('scope_kind', form)
  const metadata = resourceTypes.find(type => type.code === resource)
  return <>
    <Form.Item name="resource_type" label="资源类型" rules={[{ required: true, message: '请选择已登记资源类型' },
      { validator: (_, value: string) => resourceTypes.some(type => type.code === value && type.scope_supported) ? Promise.resolve() : Promise.reject(new Error('此角色没有当前可执行的资源范围')) }]}>
      <Select aria-label="资源类型" options={resourceTypes.map(type => ({ value: type.code, label: type.code, disabled: !type.scope_supported }))}
        onChange={() => form.setFieldsValue({ scope_kind: undefined, scope_values: undefined })} />
    </Form.Item>
    {metadata?.allowed_scope_kinds.length === 1 && metadata.allowed_scope_kinds[0] === ScopeKind.TENANT_ALL && <Alert type="info" message="该资源只支持当前企业全部资源范围" style={{ marginBottom: 12 }} />}
    <Form.Item name="scope_kind" label="数据范围" rules={[{ required: true, message: '请选择此资源支持的数据范围' },
      { validator: (_, value: string) => metadata?.allowed_scope_kinds.includes(value) ? Promise.resolve() : Promise.reject(new Error('数据范围不适用于当前资源')) }]}>
      <Select aria-label="数据范围" disabled={!metadata?.scope_supported} options={metadata?.allowed_scope_kinds.map(value => ({ value, label: labels[value] ?? value })) ?? []}
        onChange={() => form.setFieldValue('scope_values', undefined)} />
    </Form.Item>
    {kind && kind !== ScopeKind.TENANT_ALL && <Form.Item name="scope_values" label="范围标识" extra="每行一个固定真实门店或资源标识；业务访问由资源Owner的权威事实校验。" rules={[{ required: true, message: '请输入固定范围标识' },
      { validator: (_, value: string) => { const values = value?.split(/[,\n]/).map(v => v.trim()).filter(Boolean) ?? []; return values.length > 0 && values.length <= 100 && new Set(values).size === values.length && values.every(value => /^[A-Za-z0-9_:/.-]{1,100}$/.test(value)) ? Promise.resolve() : Promise.reject(new Error('请输入1—100个不重复、每项1—100字符的合法标识')) } },
    ]}><Input.TextArea rows={3} /></Form.Item>}
  </>
}
export function scopeRule(input: ScopeInput): ScopeRule {
  return { version: 1, resource_type: input.resource_type, clauses: [{ kind: input.scope_kind, values: input.scope_kind === ScopeKind.TENANT_ALL ? [] : input.scope_values!.split(/[,\n]/).map(v => v.trim()).filter(Boolean), include_root: false }] }
}
export function ScopeSummary({ rule }: { rule: ScopeRule }) {
  const labels: Record<string, string> = { TENANT_ALL: '当前企业全部资源', SPECIFIED_STORES: '指定门店', SPECIFIED_RESOURCES: '指定资源' }
  return <><Typography.Text>{rule.resource_type}</Typography.Text>{rule.clauses.map((clause, index) => <Typography.Paragraph key={index} style={{ marginBottom: 4, overflowWrap: 'anywhere' }}>
    {labels[clause.kind] ?? clause.kind}{clause.values.length ? `：${clause.values.join('、')}` : ''}
  </Typography.Paragraph>)}</>
}
