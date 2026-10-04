import { LifecycleState } from './codes.ts'
import { useState } from 'react'
import { Alert, Button, Checkbox, Input, Modal, Space, Typography } from 'antd'
import { changeCapabilityLifecycle, type CapabilityLifecycleCommand, type CapabilityLifecycleMutation, type PublishedCapability } from '../api/governance'
import { useCommand } from './useCommand'
import { Failure } from './feedback'

/** 独立Owner治理入口：确认时冻结命令，未知结果期间不能切换目标或用新命令代替。 */
export function CapabilityLifecycleControl({ application, capability: cap, owner, refreshing, refresh }: {
  application: string; capability: PublishedCapability; owner: boolean; refreshing: boolean; refresh: () => Promise<void>
}) {
  const [target, setTarget] = useState<Omit<CapabilityLifecycleCommand, 'reason' | 'command_id'>>()
  const [reason, setReason] = useState('')
  const [acknowledged, setAcknowledged] = useState(false)
  const command = useCommand<CapabilityLifecycleCommand, CapabilityLifecycleMutation>(changeCapabilityLifecycle)
  const open = () => {
    command.reset(); setReason(''); setAcknowledged(false)
    setTarget({ application_id: application, capability: cap.code, state: cap.lifecycle_state === LifecycleState.DEPRECATED ? LifecycleState.ACTIVE : LifecycleState.DEPRECATED, expected_version: cap.lifecycle_version! })
  }
  const close = () => { if (!command.busy && !command.unknown) { setTarget(undefined); command.reset() } }
  const submit = async () => {
    if (!target) return
    const result = await command.send(command_id => ({ ...target, reason: reason.trim(), command_id }))
    if (result) await refresh()
  }
  const missing = cap.lifecycle_state == null || cap.lifecycle_version == null
  return <section className="g-detail-section" aria-label="能力生命周期">
    <h3>能力生命周期</h3>
    {missing ? <Alert type="warning" message="当前响应缺少生命周期信息" description="请更新服务并重新读取；当前无法执行弃用或恢复。" /> : <>
      <p>版本 {cap.lifecycle_version} · {cap.lifecycle_state === LifecycleState.RETIRED ? '已最终退役，保留历史墓碑' : cap.lifecycle_state === LifecycleState.DEPRECATED ? '已弃用，停止新增使用' : '允许新增使用'}</p>
      {cap.lifecycle_reason && <Typography.Paragraph>最近变更原因：{cap.lifecycle_reason}</Typography.Paragraph>}
      <p>{cap.lifecycle_state === LifecycleState.RETIRED ? '最终退役后，新引用与当前访问均被拒绝；历史编码和原授权来源保留用于审计。' : '弃用会停止新角色、授权和申请使用此能力。已有授权继续受原范围、有效期与实时判权约束。'}</p>
      {cap.lifecycle_state === LifecycleState.RETIRED ? <Alert type="info" message="最终退役不可恢复" description="稳定编码与历史清单保留；解除紧急停用不会恢复此能力。" /> : owner ? <Button loading={refreshing} disabled={refreshing} onClick={open}>{cap.lifecycle_state === LifecycleState.DEPRECATED ? '恢复新增使用' : '弃用能力'}</Button> : <Typography.Text type="secondary">仅当前应用 Owner 可以弃用或恢复；分区管理委派不包含此权限。</Typography.Text>}
    </>}
    <Modal title={target?.state === LifecycleState.DEPRECATED ? '确认弃用能力' : '确认恢复新增使用'} open={!!target} width={560} centered
      onCancel={close} closable={!command.busy && !command.unknown} maskClosable={false} keyboard={!command.busy && !command.unknown}
      footer={<Space wrap><Button disabled={command.busy || command.unknown} onClick={close}>{command.result ? '关闭' : '取消'}</Button>{!command.result && <Button type="primary" loading={command.busy} disabled={!command.unknown && (!acknowledged || !reason.trim() || refreshing)} onClick={() => void submit()}>{command.unknown ? '重试原命令' : '确认提交'}</Button>}</Space>}>
      <Typography.Paragraph className="mono" style={{ overflowWrap: 'anywhere' }}>{target?.capability}</Typography.Paragraph>
      <Alert type="warning" showIcon message="作用于本权限实例内此应用的全部企业与环境" description="测试与生产使用独立实例。弃用保留已有授权；恢复不会自动授予、恢复撤销的来源或清除紧急停用。" />
      {command.unknown && <Alert style={{ marginTop: 12 }} type="warning" message="提交结果尚未确认" description="已固定原能力、目标状态、版本、原因和命令。请重试原命令核对结果，不能另建命令。" />}
      {!!command.error && <div style={{ marginTop: 12 }}><Failure error={command.error} /></div>}
      {command.result ? <Alert style={{ marginTop: 12 }} type="success" message="原命令已确认" description={`原回执版本 ${command.result.receipt.after_version}；当前版本 ${command.result.current.version}，${command.result.current.state === LifecycleState.RETIRED ? '已最终退役，不可恢复' : command.result.current.state === LifecycleState.DEPRECATED ? '已停止新增使用' : '允许新增使用'}。已有授权没有因本次操作自动回收。`} /> : <>
        <label htmlFor="capability-lifecycle-reason"><Typography.Paragraph style={{ marginTop: 16 }}>变更原因</Typography.Paragraph></label>
        <Input.TextArea id="capability-lifecycle-reason" aria-label="生命周期变更原因" value={reason} onChange={event => setReason(event.target.value)} maxLength={500} rows={3} disabled={command.busy || command.unknown} />
        <Checkbox style={{ marginTop: 12 }} checked={acknowledged} onChange={event => setAcknowledged(event.target.checked)} disabled={command.busy || command.unknown}>我已核对作用范围与已有授权的处理规则</Checkbox>
      </>}
    </Modal>
  </section>
}
