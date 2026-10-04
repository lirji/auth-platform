import { useRef, useState } from 'react'
import type { RefSelectProps } from 'antd'
import { Alert, Button, Checkbox, Descriptions, Select, Space, Table, Tag, Typography } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { createMigrationTask, migrationTasks, migrationGrants, previewRoleMigration, type CreateMigrationTask, type MigrationTaskSummary, type Role, type MigrationGrant, type RoleMigrationItem, type RoleMigrationRequest } from '../api/governance'
import { useGovernanceContext } from '../pages/GovernancePage'
import { Failure } from './feedback'
import { CapabilityList, GovernanceEmpty, GovernanceModal } from './presentation'
import { ScopeSummary } from './ScopeFields'
import { useCommand } from './useCommand'
import { RoleMigrationTaskPanel, migrationStages } from './RoleMigrationTaskPanel'

const exclusions: Record<string, string> = {
  UNSUPPORTED_SOURCE: '审批来源需要本人重新申请审批后切换', GROUP_UNAVAILABLE: '组已停用、来源隔离或缺少业务时区', GRANT_ROLE_MISMATCH: '已不属于选定旧角色',
  GRANT_NOT_ACTIVE: '授权尚未生效或已撤销', GRANT_NOT_CURRENT: '授权尚未开始', GRANT_EXPIRED: '授权已到期',
  MEMBERSHIP_UNAVAILABLE: '成员状态或代际已失效', SELF_GRANT_DENIED: '不能迁移自己的授权',
  MANAGEMENT_CEILING: '旧版或新版超出当前管理上限', CAPABILITY_UNAVAILABLE: '新版能力未发布或已停用',
  SCOPE_UNSUPPORTED: '新版能力不兼容原固定范围', DURATION_EXCEEDS_CEILING: '剩余期限超出当前管理上限',
  REQUIRES_SEPARATE_AUTHORIZATION: '新增能力须走独立授权或审批', STRICT_PARTITION_REQUIRED: '需先接管严格权限投影',
}
const sourceLabels: Record<string, string> = { DIRECT: '直接授权', GROUP: '组授权', OA_REQUEST: '审批授权' }
const stateLabels: Record<string, string> = { ACTIVE: '生效记录', PENDING: '待生效', REVOKED: '已撤销' }
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })

/** 角色列表的预览与持久任务保留上下文；每次明确选择对象，不隐含迁移全体引用。 */
export function RoleMigrationPanel({ oldRole, roles, close }: { oldRole: Role; roles: Role[]; close: () => void }) {
  const { partition, queryKey } = useGovernanceContext()
  const [target, setTarget] = useState<string>()
  const [after, setAfter] = useState<string>()
  const [selected, setSelected] = useState<string[]>([])
  const [request, setRequest] = useState<RoleMigrationRequest>()
  const [accepted, setAccepted] = useState(false)
  const [taskId, setTaskId] = useState<string>()
  const [taskLocked, setTaskLocked] = useState(false)
  const [showHistory, setShowHistory] = useState(false)
  const [historyAfter, setHistoryAfter] = useState<string>()
  const create = useCommand(async (body: CreateMigrationTask) => {
    const task = await createMigrationTask(body)
    const actual = task.items.map(item => item.old_grant_id).sort()
    if (task.tenant_id !== body.tenant_id || task.application_id !== body.application_id || task.environment !== body.environment
      || task.old_role.id !== body.old_role_id || task.new_role.id !== body.new_role_id
      || actual.join(',') !== body.grants.map(item => item.grant_id).sort().join(',')) throw new Error('迁移回执与原计划不一致，请重试原命令核对')
    return task
  })
  const frozen = create.busy || create.unknown
  const targetControl = useRef<RefSelectProps>(null)
  const targets = roles.filter(role => role.role_code === oldRole.role_code && role.version > oldRole.version)
  const sources = useQuery({ queryKey: [...queryKey, 'migration-grants', oldRole.id, after],
    queryFn: ({ signal }) => migrationGrants(partition, oldRole.id, after, signal), enabled: !taskId, retry: false, staleTime: 0, gcTime: 0 })
  const preview = useQuery({ queryKey: [...queryKey, 'migration-preview', request], enabled: !!request && !taskId && !frozen,
    queryFn: ({ signal }) => previewRoleMigration(request!, signal), retry: false, staleTime: 0, gcTime: 0 })
  const report = request && !preview.error && !preview.isFetching ? preview.data : undefined
  const history = useQuery({ queryKey: [...queryKey, 'migration-tasks', oldRole.id, historyAfter], enabled: showHistory && !taskId,
    queryFn: ({ signal }) => migrationTasks(partition, oldRole.id, historyAfter, signal), retry: false, staleTime: 0, gcTime: 0 })
  const invalidate = () => { setRequest(undefined); setAccepted(false); create.reset() }
  const save = async () => {
    if (create.unknown) {
      const recovered = await create.send(() => { throw new Error('原创建命令缺失') })
      if (recovered) setTaskId(recovered.id)
      return
    }
    if (!request || !report || !accepted || frozen && !create.unknown) return
    const result = await create.send(commandId => ({ ...partition, command_id: commandId, old_role_id: request.old_role_id, new_role_id: request.new_role_id,
      grants: report.items.filter(item => item.eligible).map(item => ({ grant_id: item.grant.id, expected_version: item.grant.version, valid_to: item.grant.valid_to, scope_hash: item.scope_hash })) }))
    if (result) setTaskId(result.id)
  }
  // 清空按钮随即禁用，主动移到仍有效的控件，保留弹层键盘关闭与焦点边界。
  const resetSelection = () => { setSelected([]); invalidate(); targetControl.current?.focus() }
  return <GovernanceModal open title={`${oldRole.role_code} · v${oldRole.version} 迁移预览`} width={960} onCancel={close} closeDisabled={frozen || taskLocked}>
    {taskId ? <RoleMigrationTaskPanel key={taskId} id={taskId} onLock={setTaskLocked} back={() => { setTaskId(undefined); setShowHistory(true); invalidate(); void history.refetch() }} /> : <>
    <Alert type="info" showIcon message="先只读核对，再明确创建迁移任务" description="先撤旧并核验真实回执，再授新，期间可能短暂无法访问。组授权保留原组和动态成员资格；审批来源须本人重新申请审批。新增能力走独立授权或审批。" />
    <Button disabled={frozen} style={{ marginTop: 12 }} onClick={() => setShowHistory(value => !value)}>查看已保存迁移任务</Button>
    {showHistory && <section aria-label="已保存迁移任务">
      {history.error ? <Failure error={history.error} retry={() => void history.refetch()} /> : <Table<MigrationTaskSummary> rowKey="id" size="small" loading={history.isFetching} pagination={false} scroll={{ x: 600 }} dataSource={history.data?.items}
        locale={{ emptyText: <GovernanceEmpty title="尚无迁移任务" description="完成预览后明确创建任务，检查点会保存到服务器。" /> }} columns={[
          { title: '任务', width: 200, render: (_, task) => <Typography.Text copyable>{task.id}</Typography.Text> },
          { title: '状态', render: (_, task) => migrationStages[task.state] ?? task.state },
          { title: '最近更新', render: (_, task) => time(task.updated_at) },
          { title: '操作', render: (_, task) => <Button type="link" disabled={frozen} onClick={() => setTaskId(task.id)}>查看／继续任务</Button> },
        ]} />}
      <Space>{historyAfter && <Button disabled={frozen} onClick={() => setHistoryAfter(undefined)}>任务首页</Button>}{history.data?.next_cursor && <Button disabled={frozen} onClick={() => setHistoryAfter(history.data!.next_cursor!)}>下一页任务</Button>}</Space>
    </section>}
    <div style={{ margin: '16px 0' }}>
      <Typography.Text strong>目标角色版本</Typography.Text>
      <Select ref={targetControl} disabled={frozen} aria-label="迁移目标角色版本" placeholder="明确选择更高的同编码版本" style={{ width: '100%', marginTop: 6 }} value={target}
        options={targets.map(role => ({ value: role.id, label: `${role.role_code} · v${role.version}` }))}
        onChange={value => { setTarget(value); invalidate() }} />
      {!targets.length && <p>当前没有更高的同编码角色版本。关闭预览，先创建需要的新版本。</p>}
    </div>
    <Typography.Title level={5}>明确选择旧版本授权</Typography.Title>
    <p>每页最多20条，已明确选择 {selected.length} / 50 条。翻页只保留已选标识，不自动选择其他页。</p>
    {sources.error ? <Failure error={sources.error} retry={() => void sources.refetch()} /> : <Table<MigrationGrant> rowKey="id" size="small" loading={sources.isFetching} pagination={false} scroll={{ x: 780 }} dataSource={sources.data?.items}
      locale={{ emptyText: <GovernanceEmpty title="旧版本没有授权引用" description="当前分区没有这一角色版本的引用，不需要执行迁移。" /> }}
      rowSelection={{ selectedRowKeys: selected, preserveSelectedRowKeys: true,
        getCheckboxProps: row => ({ disabled: frozen || selected.length >= 50 && !selected.includes(row.id) }),
        onChange: keys => { if (!frozen && keys.length <= 50) { setSelected(keys.map(String)); invalidate() } } }}
      columns={[
        { title: '成员／组', width: 220, render: (_, row) => <><Typography.Text copyable>{row.member_id ?? row.group_id}</Typography.Text>{row.member_id && <div>第 {row.member_generation} 代</div>}</> },
        { title: '来源', render: (_, row) => <>{sourceLabels[row.source_type] ?? row.source_type}<div className="g-source-id">{row.source_id}</div></> },
        { title: '固定范围', width: 120, render: (_, row) => row.scope === 'TENANT_ALL' ? '本企业全部资源' : '固定范围，预览核对' },
        { title: '到期时间', width: 180, render: (_, row) => time(row.valid_to) },
        { title: '状态', width: 110, render: (_, row) => <Tag>{stateLabels[row.state] ?? row.state}</Tag> },
      ]} />}
    <Space wrap style={{ marginTop: 12 }}>
      {after && <Button disabled={frozen} onClick={() => setAfter(undefined)}>授权首页</Button>}
      {sources.data?.next_cursor && <Button disabled={frozen} onClick={() => setAfter(sources.data!.next_cursor!)}>下一页授权引用</Button>}
      <Button disabled={frozen || !selected.length} onClick={resetSelection}>清空选择</Button>
      <Button type="primary" loading={preview.isFetching} disabled={frozen || !target || !selected.length || !!sources.error}
        onClick={() => { setAccepted(false); create.reset(); if (request) void preview.refetch(); else setRequest({ ...partition, old_role_id: oldRole.id, new_role_id: target!, grant_ids: [...selected].sort() }) }}>预览选中授权</Button>
    </Space>
    {request && preview.error && <Failure error={preview.error} retry={() => void preview.refetch()} />}
    {!!create.error && <Failure error={create.error} />}
    {create.unknown && <Alert type="warning" showIcon message="创建结果未知，保留原计划和命令" description="使用原命令核对，不能重新创建替代任务。" action={<Button loading={create.busy} onClick={() => void save()}>重试原创建命令</Button>} />}
    {report && <section aria-label="角色迁移资格结果" style={{ marginTop: 20 }}>
      <Typography.Title level={5}>选中集合核对结果</Typography.Title>
      <Alert type={report.excluded_count ? 'warning' : 'info'} showIcon message={`当前条件满足 ${report.eligible_count} 条，排除 ${report.excluded_count} 条`} description={`核对时间：${time(report.assessed_at)}。投影未独立核验；本报告不是执行票据，真正迁移前仍须重验。`} />
      <Descriptions column={1} size="small" style={{ margin: '12px 0' }} items={[
        { key: 'versions', label: '角色版本', children: `${report.old_role.role_code} · v${report.old_role.version} → v${report.new_role.version}` },
        { key: 'added', label: '新增能力', children: <CapabilityList values={report.added} /> },
        { key: 'removed', label: '移除能力', children: <CapabilityList values={report.removed} /> },
        { key: 'retained', label: '保留能力', children: <CapabilityList values={report.retained} compact /> },
      ]} />
      <Table<RoleMigrationItem> rowKey={row => row.grant.id} size="small" pagination={false} scroll={{ x: 780 }} dataSource={report.items}
        expandable={{ expandedRowRender: row => <Descriptions column={1} size="small" items={[
          { key: 'grant', label: '原授权', children: <Typography.Text copyable>{row.grant.id}</Typography.Text> },
          { key: 'source', label: '原来源', children: `${sourceLabels[row.grant.source_type] ?? row.grant.source_type} · ${row.grant.source_id}` },
          { key: 'scope', label: '固定范围', children: row.scope_rule ? <div style={{ display: 'grid', gap: 4, overflowWrap: 'anywhere' }}><ScopeSummary rule={row.scope_rule} /></div> : '当前企业全部资源' },
          { key: 'valid', label: '原时间窗', children: `${time(row.grant.valid_from)} 至 ${time(row.grant.valid_to)}` },
          { key: 'next', label: '拟新来源', children: <Typography.Text copyable>{row.proposed_source_id}</Typography.Text> },
        ]} /> }} columns={[
          { title: '成员／组', width: 220, render: (_, row) => <Typography.Text copyable>{row.grant.member_id ?? row.grant.group_id}</Typography.Text> },
          { title: '来源', width: 110, render: (_, row) => sourceLabels[row.grant.source_type] ?? row.grant.source_type },
          { title: '剩余期限', width: 130, render: (_, row) => `${row.remaining_seconds} 秒（不延长）` },
          { title: '资格／排除原因', render: (_, row) => row.eligible ? <Tag color="blue">当前条件满足</Tag> : <ul>{row.reasons.map(reason => <li key={reason}>{exclusions[reason] ?? `未识别原因：${reason}`}</li>)}</ul> },
        ]} />
      {report.eligible_count > 0 && <div style={{ display: 'grid', gap: 12, marginTop: 16 }}>
        <Checkbox checked={accepted} disabled={frozen} onChange={event => setAccepted(event.target.checked)}>仅为当前条件满足的 {report.eligible_count} 项创建任务，排除 {report.excluded_count} 项；我接受先撤旧再授新的拒绝窗口</Checkbox>
        <Button type="primary" style={{ justifySelf: 'start' }} disabled={!accepted || frozen} loading={create.busy} onClick={() => void save()}>创建选中资格的迁移任务</Button>
      </div>}
    </section>}
    </>}
  </GovernanceModal>
}
