import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Descriptions, Modal, Space, Table, Tag, Typography } from 'antd'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { advanceMigrationTask, cancelMigrationTask, migrationTask, retryMigrationProjection, type AdvanceMigrationTask, type CancelMigrationTask, type MigrationTaskItem } from '../api/governance'
import { useGovernanceContext } from '../pages/GovernancePage'
import { useCommand } from './useCommand'
import { Failure } from './feedback'
import { ScopeSummary } from './ScopeFields'
import { GrantSourceType, MigrationAction, MigrationStage } from './codes'

export const migrationStages: Record<string, string> = {
  RUNNING: '处理中', COMPLETED: '迁移已核验', FINISHED_WITH_FAILURES: '处理结束，存在失败', CANCELLED: '任务已取消',
  READY_TO_REVOKE: '待撤销旧授权', WAIT_REVOKE_CONFIRM: '等待真实撤权回执', READY_TO_GRANT: '已确认撤权，待授新',
  WAIT_NEW_CONFIRM: '等待新授权确认', FAILED: '迁移失败',
}
const reasons: Record<string, string> = {
  ORIGINAL_CHANGED: '原授权被其他操作撤销或变更，任务不会补回权限', MEMBERSHIP_UNAVAILABLE: '成员状态或代际已失效',
  GRANT_EXPIRED: '原授权已到期，不能续期', GRANT_NOT_CURRENT: '原授权尚未开始', MANAGEMENT_CEILING: '超出当前管理上限',
  SELF_GRANT_DENIED: '不能迁移自己的授权', CAPABILITY_UNAVAILABLE: '新版能力未发布或已停用',
  SCOPE_UNSUPPORTED: '目标能力不兼容固定范围', DURATION_EXCEEDS_CEILING: '剩余期限超出管理上限',
  REQUIRES_SEPARATE_AUTHORIZATION: '新增能力须独立授权', STRICT_PARTITION_REQUIRED: '严格分区不可用',
  UNSUPPORTED_SOURCE: '此来源须单独处理', OA_APPROVAL_REQUIRED: '新版批准已撤回、到期或当前资格失效', GROUP_UNAVAILABLE: '组已停用、来源隔离或缺少业务时区', GRANT_ROLE_MISMATCH: '原角色已变更', GRANT_NOT_ACTIVE: '原授权未生效',
  PROJECTION_PROCESSING: '投影仍在处理，可刷新状态后再次推进', PROJECTION_BLOCKED: '投影已阻断，修复依赖后受控重试',
  REVOCATION_PROOF_CHANGED: '原撤权证明变化，需要重新核对', LINEAGE_CONFLICT: '迁移来源已存在，不覆盖原来源',
  NEW_GRANT_CHANGED: '新授权被撤销或变更，任务不会重新创建', TASK_CANCELLED: '已停止后续步骤，已提交效果保留',
}
const terminal = new Set<string>([MigrationStage.COMPLETED, MigrationStage.FAILED, MigrationStage.CANCELLED])
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
/** 旧DIRECT响应仍可读；旧节点缺少GROUP元数据时不能伪造第0代个人或继续写入。 */
const knownSource = (item: MigrationTaskItem) => item.member_id
  ? item.member_generation > 0 && !item.group_id && ((!item.source_type || item.source_type === GrantSourceType.DIRECT) && !item.replacement_request_id || item.source_type === GrantSourceType.OA_REQUEST && !!item.replacement_request_id)
  : item.member_generation === 0 && !!item.group_id && item.source_type === GrantSourceType.GROUP
type Action = { kind: typeof MigrationAction.ADVANCE; body: AdvanceMigrationTask } | { kind: typeof MigrationAction.CANCEL; body: CancelMigrationTask }
  | { kind: typeof MigrationAction.RETRY; command: string; stream: 'POLICY' | 'DIRECTORY' }

/** 前台一轮最多50个单阶段请求；中断保留服务器检查点，不扮演长期后台管理员。 */
export function RoleMigrationTaskPanel({ id, back, onLock }: { id: string; back: () => void; onLock: (locked: boolean) => void }) {
  const { partition, queryKey } = useGovernanceContext()
  const cache = useQueryClient()
  const key = [...queryKey, 'migration-task', id]
  const [running, setRunning] = useState(false)
  const [batchError, setBatchError] = useState<unknown>()
  const stop = useRef(false)
  const refreshControl = useRef<HTMLButtonElement>(null)
  const failedRead = useRef(false)
  const command = useCommand(async (action: Action) => {
    await cache.cancelQueries({ queryKey: key })
    const result = action.kind === MigrationAction.ADVANCE ? await advanceMigrationTask(id, action.body)
      : action.kind === MigrationAction.CANCEL ? await cancelMigrationTask(id, action.body)
        : await retryMigrationProjection(partition, action.command, action.stream).then(() => migrationTask(partition, id))
    if (result.id !== id || result.tenant_id !== partition.tenant_id || result.application_id !== partition.application_id || result.environment !== partition.environment)
      throw new Error('迁移回执不属于当前任务，请重试原命令核对')
    cache.setQueryData(key, result)
    return result
  })
  const locked = running || command.busy || command.unknown
  useEffect(() => { onLock(locked) }, [locked, onLock])
  const detail = useQuery({ queryKey: key, queryFn: ({ signal }) => migrationTask(partition, id, signal),
    enabled: !locked, retry: false, staleTime: 0, gcTime: 0,
    refetchInterval: query => !locked && query.state.data?.state === MigrationStage.RUNNING ? 5000 : false })
  const task = !detail.error ? detail.data : undefined
  const sourcesKnown = task?.items.every(knownSource) ?? false
  // 详情和错误重试会卸载原按钮；把焦点放回稳定操作，保持Esc与模态焦点边界。
  useEffect(() => { refreshControl.current?.focus() }, [])
  useEffect(() => {
    if (detail.error) { failedRead.current = true; return }
    if (failedRead.current && detail.data && !detail.isFetching) {
      failedRead.current = false
      refreshControl.current?.focus()
    }
  }, [detail.error, detail.data, detail.isFetching])
  const run = async () => {
    if (locked || !task || !sourcesKnown || task.state !== MigrationStage.RUNNING) return
    stop.current = false; setRunning(true); setBatchError(undefined)
    try {
      const current = await migrationTask(partition, id)
      cache.setQueryData(key, current)
      // 发请求前的新读取也要核对来源，兼容节点在两次读取间变化时不能继续写入。
      if (!current.items.every(knownSource)) return
      for (const item of current.items.filter(item => !terminal.has(item.state))) {
        if (stop.current) break
        const result = await command.send(commandId => ({ kind: MigrationAction.ADVANCE, body: { ...partition, command_id: commandId, item_id: item.id, expected_version: item.version } }))
        if (!result) break
      }
    } catch (error) { setBatchError(error) }
    finally { setRunning(false) }
  }
  const cancel = () => {
    if (!task || locked || !sourcesKnown) return
    Modal.confirm({ title: '取消后续迁移步骤？', content: '已经撤销的旧授权不会恢复。已经提交的新授权可能继续生效，若要撤销它，请在授权来源中另行撤权。',
      okText: '停止后续步骤', cancelText: '保留任务', onOk: async () => { await command.send(commandId => ({ kind: MigrationAction.CANCEL, body: { ...partition, command_id: commandId, expected_version: task.version } })) } })
  }
  return <section aria-label="角色迁移任务详情">
    <Space wrap><Button disabled={locked} onClick={back}>返回预览与历史任务</Button><Button ref={refreshControl} disabled={locked} loading={detail.isFetching} onClick={() => void detail.refetch()}>刷新任务状态</Button></Space>
    {!!detail.error && <Failure error={detail.error} retry={() => void detail.refetch()} />}
    {!!batchError && <Failure error={batchError} retry={() => void run()} />}
    {!!command.error && <Failure error={command.error} />}
    {command.unknown && <Alert type="warning" showIcon message="本次命令结果未知" description="不要创建替代任务或重新授权。使用原命令重试核对；服务器已提交的检查点会保留。" action={<Button loading={command.busy} onClick={() => void command.send(() => { throw new Error('原迁移命令缺失') })}>重试原迁移命令</Button>} />}
    {task && <>
      {!sourcesKnown && <Alert type="warning" showIcon message="来源信息缺失，当前任务只读" description="连接兼容服务并刷新核对后才能继续。未确认来源不解释为个人授权。" />}
      <Typography.Title level={5}>{task.old_role.role_code} · v{task.old_role.version} → v{task.new_role.version}</Typography.Title>
      <Alert showIcon type={task.failed_count ? 'warning' : task.state === MigrationStage.COMPLETED ? 'success' : 'info'} message={migrationStages[task.state] ?? task.state}
        description={`共 ${task.items.length} 项：已核验 ${task.completed_count}，失败 ${task.failed_count}，取消 ${task.cancelled_count}，待处理 ${task.waiting_count}。每轮每项推进一个阶段，等待真实投影回执期间可短暂无法访问。`} />
      <Descriptions column={1} size="small" style={{ margin: '12px 0' }} items={[
        { key: 'id', label: '持久任务', children: <Typography.Text copyable>{task.id}</Typography.Text> },
        { key: 'command', label: '原创建命令', children: <Typography.Text copyable>{task.command_id}</Typography.Text> },
        { key: 'updated', label: '最近检查点', children: time(task.updated_at) },
      ]} />
      <Space wrap style={{ marginBottom: 12 }}>
        <Button type="primary" disabled={locked || !sourcesKnown || task.state !== MigrationStage.RUNNING} onClick={() => void run()}>推进本轮</Button>
        {running && <Button onClick={() => { stop.current = true }}>停止继续发请求</Button>}
        <Button danger disabled={locked || !sourcesKnown || task.state !== MigrationStage.RUNNING} onClick={cancel}>取消后续迁移</Button>
        {task.items.some(item => item.reason === 'PROJECTION_BLOCKED') && <>
          <Button disabled={locked} onClick={() => void command.send(commandId => ({ kind: MigrationAction.RETRY, command: commandId, stream: 'POLICY' }))}>修复后重试策略投影</Button>
          <Button disabled={locked} onClick={() => void command.send(commandId => ({ kind: MigrationAction.RETRY, command: commandId, stream: 'DIRECTORY' }))}>修复后重试目录投影</Button>
        </>}
      </Space>
      {task.state === MigrationStage.CANCELLED && <Alert type="warning" showIcon message="后续步骤已停止，已有授权效果保留" description="旧授权不会自动恢复。下表仍展示已经提交的新授权，投影可能继续处理；取消不是授权回滚。" />}
      <Table<MigrationTaskItem> rowKey="id" size="small" pagination={false} scroll={{ x: 800 }} dataSource={task.items}
        expandable={{ expandedRowRender: item => <Descriptions column={1} size="small" items={[
          { key: 'old', label: '原授权', children: <Typography.Text copyable>{item.old_grant_id}</Typography.Text> },
          { key: 'source', label: '原来源', children: item.original_source_id },
          { key: 'source-type', label: '来源类型', children: !knownSource(item) ? '来源信息缺失' : item.group_id ? '组授权（当前动态成员资格）' : item.source_type === GrantSourceType.OA_REQUEST ? '审批授权（新版独立批准）' : '直接授权' },
          { key: 'approval', label: '新版批准申请', children: item.replacement_request_id ? <Typography.Text copyable>{item.replacement_request_id}</Typography.Text> : '此来源无需审批关联' },
          { key: 'scope', label: '固定范围', children: item.scope_rule ? <div style={{ display: 'grid', gap: 4, overflowWrap: 'anywhere' }}><ScopeSummary rule={item.scope_rule} /></div> : '当前企业全部资源' },
          { key: 'valid', label: '原时间窗', children: `${time(item.valid_from)} 至 ${time(item.valid_to)}（不续期）` },
          { key: 'lineage', label: '新来源谱系', children: <Typography.Text copyable>{item.new_source_id}</Typography.Text> },
          { key: 'revoke', label: '撤权回执', children: item.revocation_operation_id ?? '尚未确认真实撤权' },
          { key: 'new', label: '新授权', children: item.new_grant ? <Typography.Text copyable>{item.new_grant.id}</Typography.Text> : '尚未提交' },
          { key: 'new-operation', label: '新授权回执', children: item.new_operation_id ?? '尚未确认真实生效' },
        ]} /> }} columns={[
          { title: '成员／组', width: 220, render: (_, item) => <><Typography.Text copyable>{item.member_id ?? item.group_id ?? '来源信息缺失'}</Typography.Text><div>{!knownSource(item) ? '当前来源尚未确认' : item.group_id ? '固定组，成员资格动态核对' : `第 ${item.member_generation} 代`}</div></> },
          { title: '迁移阶段', width: 190, render: (_, item) => <Tag color={item.state === MigrationStage.FAILED ? 'error' : item.state === MigrationStage.COMPLETED ? 'success' : undefined}>{migrationStages[item.state] ?? item.state}</Tag> },
          { title: '原截止', width: 180, render: (_, item) => time(item.valid_to) },
          { title: '新授权状态／原因', render: (_, item) => <>{item.new_grant ? ({ PENDING: '新授权待生效', ACTIVE: '新授权生效记录', REVOKED: '新授权已撤销' }[item.new_grant.state]) : '尚未授新'}{item.reason && <div>{reasons[item.reason] ?? `未识别原因：${item.reason}`}</div>}</> },
        ]} />
    </>}
  </section>
}
