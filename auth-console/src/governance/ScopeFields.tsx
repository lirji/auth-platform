import { Form, Input, Select, Typography } from 'antd'
import { ScopeKind } from './codes'
import type { ScopeRule } from '../api/governance'

export interface ScopeInput { resource_type: string; scope_kind: string; scope_values?: string }
/** 目前试点仅绑定全企业/指定门店/指定资源；不提供未有Owner适配器的SELF或供应商规则。 */
export function ScopeFields({ resources }: { resources: string[] }) {
  const form = Form.useFormInstance()
  const kind = Form.useWatch('scope_kind', form)
  return <>
    <Form.Item name="resource_type" label="资源类型" rules={[{ required: true, message: '请选择已登记资源类型' }]}><Select aria-label="资源类型" options={resources.map(value => ({ value, label: value }))} /></Form.Item>
    <Form.Item name="scope_kind" label="数据范围" rules={[{ required: true }]}><Select aria-label="数据范围" options={[
      { value: 'SPECIFIED_STORES', label: '指定门店' }, { value: 'SPECIFIED_RESOURCES', label: '指定资源' }, { value: 'TENANT_ALL', label: '当前企业全部资源' },
    ]} /></Form.Item>
    {kind !== ScopeKind.TENANT_ALL && <Form.Item name="scope_values" label="范围标识" extra="每行一个真实门店或资源标识；后端按所属租户重新检查。" rules={[{ required: true, message: '请输入固定范围标识' },
      { validator: (_, value: string) => { const values = value?.split(/[,\n]/).map(v => v.trim()).filter(Boolean) ?? []; return values.length > 0 && values.length <= 100 && new Set(values).size === values.length ? Promise.resolve() : Promise.reject(new Error('请输入1—100个不重复标识')) } },
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
