import { useState } from 'react'
import { Alert, Button, Form, Input, Select, Space, Typography } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { createReview, reviewResponsibles } from '../api/accessReview'
import type { ReviewCreate as Create } from './accessReview'
import type { PersonnelReport } from './personnelImpact'
import { useGovernanceContext } from '../pages/GovernancePage'
import { applicationSearch } from './context'
import { useCommand } from './useCommand'
import { GovernanceModal } from './presentation'
import { Failure } from './feedback'

/** 来源选择由真实当前页提供，不能把勾选页宣称为全部历史权限。 */
export function ReviewCreate({ report, selected, sourceCursor }: { report: PersonnelReport; selected: string[]; sourceCursor?: string }) {
  const { partition, queryKey } = useGovernanceContext(), navigate = useNavigate(), [params] = useSearchParams()
  const [open, setOpen] = useState(false), [form] = Form.useForm<{ responsible: string; reason: string }>()
  const query = useQuery({ queryKey: [...queryKey, 'review-responsibles'], queryFn: ({ signal }) => reviewResponsibles(partition, signal), enabled: open, gcTime: 0, staleTime: 0, retry: false })
  const command = useCommand<Create, Awaited<ReturnType<typeof createReview>>>(createReview), frozen = command.busy || command.unknown
  const submit = async () => {
    let values = { responsible: '', reason: '' }
    if (!command.unknown) {
      // 字段错误由表单展示，保留输入继续修正，不让校验拒绝成为页面异常。
      try { values = await form.validateFields() } catch (failure) { if (failure && typeof failure === 'object' && 'errorFields' in failure) return; throw failure }
    }
    const result = await command.send(command_id => {
      const [responsible_membership_id, generation] = values.responsible.split(':')
      return { ...partition, command_id, membership_id: report.target.membership_id, responsible_membership_id, responsible_generation: Number(generation), basis_hash: report.basis_hash, source_cursor: sourceCursor ?? null, grant_ids: [...selected], reason: values.reason.trim() }
    })
    if (result) { setOpen(false); const next = applicationSearch(params, partition.application_id, partition.environment); next.set('review', result.id); navigate(`/governance/reviews?${next}`) }
  }
  return <><Space wrap style={{ marginTop: 12 }}><Typography.Text>本页已选 {selected.length} 条来源（最多100条）</Typography.Text><Button disabled={!report.complete || !selected.length} onClick={() => { command.reset(); setOpen(true) }}>创建人工复核</Button></Space>
    <GovernanceModal title="创建人工权限复核" open={open} closeDisabled={frozen} onCancel={() => { if (!frozen) setOpen(false) }} footer={<Button type="primary" loading={command.busy} disabled={!command.unknown && (!!query.error || query.isFetching || !query.data?.length)} onClick={() => void submit()}>{command.unknown ? '重试原创建命令' : '确认创建复核'}</Button>}>
      <Alert type="info" showIcon message={`固定该人员与本页所选 ${selected.length} 条来源`} description="负责人逐条记录保留、撤销或需调查；本次选择不包含其他页或今后新增来源。组来源撤销影响全组，决定时另需明确确认。" />
      <Typography.Paragraph style={{ marginTop: 16, overflowWrap: 'anywhere' }}>目标成员：{report.target.membership_id} · 第 {report.target.generation} 代</Typography.Paragraph>
      {query.error ? <Failure error={query.error} retry={() => void query.refetch()} /> : <Form form={form} layout="vertical" disabled={frozen} style={{ marginTop: 16 }}><Form.Item name="responsible" label="当前合格负责人" rules={[{ required: true, message: '请选择具有当前管理与独立诊断资格的负责人' }]}><Select loading={query.isFetching} placeholder="显式选择负责人" options={query.data?.map(r => ({ value: `${r.membership_id}:${r.generation}`, label: `${r.membership_id} · 第 ${r.generation} 代` }))} /></Form.Item><Form.Item name="reason" label="复核原因" rules={[{ required: true, whitespace: true, max: 500, message: '填写1–500字原因' }]}><Input.TextArea rows={3} maxLength={500} showCount /></Form.Item></Form>}
      {!!command.error && <Failure error={command.error} />}{command.unknown && <Alert type="warning" message="创建结果尚未确认，请使用原命令重试" description="保持原成员、来源、负责人和依据，不重新生成创建命令。" />}
    </GovernanceModal></>
}
