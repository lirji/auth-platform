import { useState } from 'react'
import { Alert, Button, Checkbox, Input, Modal, Space, Table, Tag, Typography } from 'antd'
import { capabilityRetirement, retirementReferences, retireCapability, type Partition, type RetirementReport, type RetirementReferences, type RetirementCommand, type CapabilityLifecycleMutation } from '../api/governance'
import { Failure } from './feedback'
import { useCommand } from './useCommand'
import { LifecycleState, RetirementProofState } from './codes'

const labels: Record<string, string> = { MENU: '当前菜单', ROLE: '角色版本', GRANT: '授权来源', DELEGATION: '管理委派', POLICY: '申请策略', REQUEST: '在途申请', MIGRATION: '角色迁移', LEGACY_PROJECTION: '旧投影', PROJECTION: '可靠投影', RECEIPT: '真实回执', FENCE: '策略栅栏', DIRECTORY_FENCE: '人员栅栏', DIRECTORY_PROJECTION: '人员投影', EXECUTION: '执行引用' }
const proofReasons: Record<string, string> = { NOT_CONFIGURED: '未接入独立受信运行核验', PROOF_UNAVAILABLE: '运行核验证明不可读取', INVALID_PROOF: '证明格式无效', INVALID_SIGNATURE: '签名不可信', BASIS_MISMATCH: '证明与当前目录或生命周期不一致', EXPIRED_OR_INVALID_TIME: '证明已到期或时间无效', INCOMPLETE_MAPPING: '接口映射未完整核验', TARGET_MISMATCH: '部署目标覆盖不完整', API_REFERENCES_REMAIN: '项目接口仍引用此能力' }

/** 目录详情中的引用检查；Owner全应用摘要和当前分区诊断明细分别由真实接口授权。 */
export function CapabilityRetirementPanel({ partition, capability, owner, refresh }: { partition: Partition; capability: string; owner: boolean; refresh: () => Promise<void> }) {
  const [report, setReport] = useState<RetirementReport>()
  const [references, setReferences] = useState<RetirementReferences>()
  const [error, setError] = useState<unknown>()
  const [detailError, setDetailError] = useState<unknown>()
  const [busy, setBusy] = useState(false)
  const [detailBusy, setDetailBusy] = useState(false)
  const [target, setTarget] = useState<Omit<RetirementCommand, 'reason' | 'command_id'>>()
  const [reason, setReason] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const command = useCommand<RetirementCommand, CapabilityLifecycleMutation>(retireCapability)
  const read = async () => {
    setBusy(true); setError(undefined); setReport(undefined)
    try { setReport(await capabilityRetirement(partition.application_id, capability)) } catch (e) { setError(e) } finally { setBusy(false) }
  }
  const details = async (cursor?: string) => {
    setDetailBusy(true); setDetailError(undefined)
    if (!cursor) setReferences(undefined)
    try { setReferences(await retirementReferences(partition, capability, cursor)) } catch (e) { setDetailError(e); setReferences(undefined) } finally { setDetailBusy(false) }
  }
  const open = () => {
    if (!report?.eligible) return
    command.reset(); setReason(''); setConfirmed(false)
    setTarget({ application_id: report.application_id, capability: report.capability, expected_version: report.lifecycle_version, basis_hash: report.basis_hash })
  }
  const close = () => { if (!command.busy && !command.unknown) { setTarget(undefined); command.reset() } }
  const submit = async () => {
    if (!target) return
    const result = await command.send(command_id => ({ ...target, reason: reason.trim(), command_id }))
    if (result) { await refresh(); await read() }
  }
  return <section className="g-detail-section" aria-label="能力引用与退役">
    <h3>引用退出与最终退役</h3>
    <p>停止新增使用后，需退出当前与未来授权、申请、迁移、投影和项目接口引用。历史角色与已终结来源会保留。</p>
    <Space wrap>{owner && <Button loading={busy} disabled={command.unknown || command.busy} onClick={() => void read()}>检查全应用退出条件</Button>}<Button loading={detailBusy} onClick={() => void details()}>查看当前分区引用</Button></Space>
    {!!error && <Failure error={error} />}
    {report && <>
      <p>目录版本 {report.manifest_version} · 生命周期版本 {report.lifecycle_version} · {report.complete ? '引用依据完整' : '引用过多，依据不完整'}</p>
      <Table size="small" rowKey="kind" dataSource={report.counts} pagination={false} scroll={{ x: 360 }} columns={[
        { title: '引用类别', dataIndex: 'kind', render: (v: string) => labels[v] || v }, { title: '阻断退役', dataIndex: 'blocking' }, { title: '历史审计', dataIndex: 'historical' },
      ]} />
      <Alert style={{ marginTop: 12 }} type={report.proof_state === RetirementProofState.PROVEN ? 'success' : 'warning'} message={report.proof_state === RetirementProofState.PROVEN ? '项目接口退出已核验' : '项目接口退出尚未证明'}
        description={report.proof_reason ? proofReasons[report.proof_reason] || report.proof_reason : `受信证明截止：${report.proof_valid_until}`} />
      {report.lifecycle_state === LifecycleState.RETIRED ? <p>已最终退役，历史墓碑不可恢复。</p> : <Button style={{ marginTop: 12 }} danger disabled={!report.eligible || busy || command.unknown} onClick={open}>确认最终退役</Button>}
      {!report.eligible && report.lifecycle_state !== LifecycleState.RETIRED && <p>当前条件不满足，先处理阻断引用并重新检查。紧急停用可通过原受控入口独立执行。</p>}
    </>}
    {!!detailError && <Failure error={detailError} />}
    {references && <>
      <Typography.Paragraph style={{ marginTop: 12 }}>当前分区引用，仅展示你已获诊断授权的数据。</Typography.Paragraph>
      <Table size="small" rowKey={r => `${r.kind}:${r.id}`} dataSource={references.items} pagination={false} scroll={{ x: 620 }} columns={[
        { title: '类别', dataIndex: 'kind', render: (v: string) => labels[v] || v }, { title: '引用标识', dataIndex: 'id', render: (v: string) => <span className="mono" style={{ overflowWrap: 'anywhere' }}>{v}</span> },
        { title: '状态', dataIndex: 'state' }, { title: '退出条件', dataIndex: 'blocking', render: (v: boolean) => <Tag color={v ? 'orange' : 'default'}>{v ? '阻断' : '历史保留'}</Tag> },
      ]} />
      {references.next_cursor && <Button loading={detailBusy} onClick={() => void details(references.next_cursor!)}>下一页引用</Button>}
    </>}
    <Modal open={!!target} title="确认最终退役能力" width={560} centered onCancel={close} closable={!command.busy && !command.unknown} maskClosable={false} keyboard={!command.busy && !command.unknown}
      footer={<Space wrap><Button disabled={command.busy || command.unknown} onClick={close}>{command.result ? '关闭' : '取消'}</Button>{!command.result && <Button danger type="primary" loading={command.busy} disabled={!command.unknown && (!confirmed || !reason.trim())} onClick={() => void submit()}>{command.unknown ? '重试原命令' : '提交最终退役'}</Button>}</Space>}>
      <Typography.Paragraph className="mono" style={{ overflowWrap: 'anywhere' }}>{target?.capability}</Typography.Paragraph>
      <Alert type="warning" message="最终退役不可恢复" description="作用于本实例全部企业与环境。保留稳定编码、原清单和历史审计；此命令不会删除或补回授权。提交时会重验引用与受信证明。" />
      {command.unknown && <Alert style={{ marginTop: 12 }} type="warning" message="结果尚未确认，只能重试原命令" />}
      {!!command.error && <Failure error={command.error} />}
      {command.result ? <Alert style={{ marginTop: 12 }} type="success" message="已确认最终退役" description={`原回执版本 ${command.result.receipt.after_version}，当前版本 ${command.result.current.version}`} /> : <>
        <label htmlFor="retirement-reason"><Typography.Paragraph style={{ marginTop: 12 }}>退役原因</Typography.Paragraph></label>
        <Input.TextArea id="retirement-reason" value={reason} maxLength={500} rows={3} disabled={command.busy || command.unknown} onChange={e => setReason(e.target.value)} />
        <Checkbox style={{ marginTop: 12 }} checked={confirmed} disabled={command.busy || command.unknown} onChange={e => setConfirmed(e.target.checked)}>已核对全部退出条件及不可恢复的作用范围</Checkbox>
      </>}
    </Modal>
  </section>
}
