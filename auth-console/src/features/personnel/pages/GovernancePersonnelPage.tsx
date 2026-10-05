import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Input,
  Skeleton,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { personnelImpact } from '../../../api/governance';
import {
  DirectoryAggregate,
  PersonnelOutcome,
  PersonnelStatus,
  type PersonnelAssignment,
  type PersonnelChange,
  type PersonnelFacts,
  type PersonnelGrantSource,
} from '../model/personnelImpact';
import { ReviewCreate } from '../../reviews/components/ReviewCreate';
import { ReceiptState } from '../../governance/shared/codes';
import { Failure } from '../../governance/shared/feedback';
import {
  GovernanceEmpty,
  GovernanceModal,
  PermissionStatus,
} from '../../governance/shared/presentation';
import { applicationSearch } from '../../governance/shared/context';
import { useGovernanceContext } from '../../governance/shell/GovernancePage';
import { PermissionDetails } from '../../permissions/pages/GovernancePermissionsPage';

const memberLabels: Record<string, string> = {
  ACTIVE: '有效',
  LEFT: '已退出企业',
  SUSPENDED: '成员已暂停',
};
const uuid = (v: string) =>
  /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(v);
const code = (v: string) => (
  <Typography.Text style={{ overflowWrap: 'anywhere' }} copyable>
    {v}
  </Typography.Text>
);

/** 人员核对只读取真实诊断接口，企业和全局状态、多条完整来源各自展示。 */
export default function GovernancePersonnelPage() {
  const { partition, queryKey } = useGovernanceContext();
  const [params, setParams] = useSearchParams(),
    member = params.get('member') ?? '';
  const changeCursor = params.get('change_cursor') ?? undefined,
    sourceCursor = params.get('source_cursor') ?? undefined;
  const [reviewSelection, setReviewSelection] = useState<string[]>([]);
  const [draft, setDraft] = useState(member),
    [selectedChange, setSelectedChange] = useState(''),
    [selectedSource, setSelectedSource] = useState('');
  useEffect(() => {
    setDraft(member);
    setSelectedChange('');
    setSelectedSource('');
  }, [
    member,
    changeCursor,
    sourceCursor,
    partition.tenant_id,
    partition.application_id,
    partition.environment,
  ]);
  const query = useQuery({
    queryKey: [...queryKey, 'personnel-impact', member, changeCursor, sourceCursor],
    queryFn: ({ signal }) => personnelImpact(partition, member, changeCursor, sourceCursor, signal),
    enabled: uuid(member),
    staleTime: 0,
    gcTime: 0,
    retry: false,
    refetchOnWindowFocus: false,
  });
  // 读取失败、切换或重新读取立即隐藏旧人员内容，迟到数据仍由完整查询键隔离。
  const report = !query.error && !query.isFetching ? query.data : undefined;
  useEffect(() => {
    setReviewSelection([]);
  }, [
    report?.basis_hash,
    member,
    sourceCursor,
    partition.tenant_id,
    partition.application_id,
    partition.environment,
  ]);
  const change = report?.changes.find(
    (c) => `${c.source_id}/${c.partition_sequence}` === selectedChange,
  );
  const source = report?.sources.find((s) => s.grant.grant_id === selectedSource);
  const select = () => {
    const next = applicationSearch(params, partition.application_id, partition.environment);
    next.set('member', draft.trim());
    setParams(next);
  };
  const nextPage = (key: 'change_cursor' | 'source_cursor', cursor?: string) => {
    const next = new URLSearchParams(params);
    if (cursor) next.set(key, cursor);
    else next.delete(key);
    setParams(next);
  };
  const refresh = () => {
    setSelectedChange('');
    setSelectedSource('');
    if (changeCursor || sourceCursor) {
      const next = new URLSearchParams(params);
      next.delete('change_cursor');
      next.delete('source_cursor');
      setParams(next);
    } else void query.refetch();
  };
  return (
    <>
      <Card
        title="选择核对人员"
        extra={
          uuid(member) && (
            <Button onClick={refresh} loading={query.isFetching}>
              重新读取当前依据
            </Button>
          )
        }
      >
        <Alert
          type="info"
          showIcon
          message="人工核对，不自动调整岗位角色"
          description="OA是人员与组织事实权威；个人例外来源保持，组来源按当前任职动态检查。此页不能暂停、恢复人员或自动授予权限。"
        />
        <label htmlFor="personnel-member">
          <Typography.Paragraph style={{ marginTop: 16 }}>当前企业成员编号</Typography.Paragraph>
        </label>
        <Space.Compact style={{ width: '100%' }}>
          <Input
            id="personnel-member"
            aria-label="核对成员编号"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onPressEnter={() => {
              if (uuid(draft.trim())) select();
            }}
            placeholder="从成员授权来源详情进入，或输入完整成员UUID"
          />
          <Button type="primary" disabled={!uuid(draft.trim())} onClick={select}>
            核对人员
          </Button>
        </Space.Compact>
        {!uuid(member) && (
          <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
            输入同企业的完整成员编号。目标和当前诊断资格由服务端核验。
          </Typography.Paragraph>
        )}
      </Card>
      {query.error ? (
        <Failure error={query.error} retry={refresh} />
      ) : uuid(member) && query.isFetching ? (
        <Card>
          <Skeleton active />
        </Card>
      ) : (
        report && (
          <>
            {!report.complete && (
              <Alert
                type="warning"
                showIcon
                message="核对依据不完整"
                description="依据超过当前有界读取上限，不能把本页当作全部来源或据此完成权限复核。请联系负责人员缩小核对范围。"
              />
            )}
            <Card title="当前人员事实">
              <Descriptions
                column={1}
                items={[
                  { label: '企业成员', children: code(report.target.membership_id) },
                  {
                    label: '企业内状态',
                    children: <Tag>{memberLabels[report.target.status]}</Tag>,
                  },
                  {
                    label: '成员代际 / 版本',
                    children: `${report.target.generation} / ${report.target.version}`,
                  },
                  {
                    label: '全局主体状态',
                    children: (
                      <Tag>
                        {report.target.principal_status === PersonnelStatus.SUSPENDED
                          ? '主体已全局暂停，影响关联企业'
                          : '主体正常'}
                      </Tag>
                    ),
                  },
                  { label: '主体版本', children: report.target.principal_version },
                  {
                    label: '成员有效期',
                    children: `${report.target.valid_from} → ${report.target.valid_to ?? '未设置截止'}`,
                  },
                  { label: '本次核对时间', children: report.checked_at },
                  { label: '当前依据摘要', children: code(report.basis_hash) },
                ]}
              />
              <Alert
                type="info"
                message="人员暂不可用不等于所有来源已撤销；来源状态、投影和真实撤权回执分别核对。此页解释不能作为业务资源访问许可。"
              />
            </Card>
            <Card title="当前权威目录事实">
              {!report.directories.length ? (
                <GovernanceEmpty
                  title="暂无该成员的OA目录事实"
                  description="平台成员可能来自既有受控登记；没有目录记录不表示同步成功。"
                />
              ) : (
                report.directories.map((d) => (
                  <section key={`${d.source_id}/${d.aggregate_id}`} className="g-detail-section">
                    <p>
                      聚合 {d.aggregate_id} · 当前版本 {d.aggregate_version}
                    </p>
                    <Facts value={d.facts} />
                    {d.history_missing && (
                      <Alert
                        type="warning"
                        message="此前变更未完整记录"
                        description="旧消费者或首次收到高版本时缺少历史证据；当前内容不能反向补造以前的变更。"
                      />
                    )}
                  </section>
                ))
              )}
              {report.directory_sources.map((s) => (
                <section key={s.source_id} className="g-detail-section">
                  <h3>权威来源状态</h3>
                  <p>本地连续检查点：{s.last_sequence} · 源端同步状态未知</p>
                  {s.quarantined && (
                    <Alert
                      type="error"
                      message="来源已隔离，人员事实可能未更新"
                      description={
                        s.conflict_reasons.join(' / ') || '请联系目录来源负责人员核对冲突。'
                      }
                    />
                  )}
                  {!s.quarantined && (
                    <Typography.Paragraph type="secondary">
                      仅本地检查点不能证明源端同步完成；拉取失败或不完整快照应由目录负责人员核对。
                    </Typography.Paragraph>
                  )}
                </section>
              ))}
            </Card>
            <Card title="已接受的人员与组织变更">
              <Typography.Paragraph type="secondary">
                历史事件与当前事实分开展示。过期版本仅保留OBSOLETE证据，不能恢复当前人员状态。
              </Typography.Paragraph>
              <Table<PersonnelChange>
                rowKey={(c) => `${c.source_id}/${c.partition_sequence}`}
                dataSource={report.changes}
                pagination={false}
                scroll={{ x: 800 }}
                locale={{ emptyText: '尚无必要变更证据；此前历史可能未记录。' }}
                columns={[
                  {
                    title: '序号 / 聚合版本',
                    render: (_, c) => `${c.partition_sequence} / ${c.aggregate_version}`,
                  },
                  {
                    title: '变更对象',
                    render: (_, c) =>
                      c.aggregate_type === DirectoryAggregate.EMPLOYEE
                        ? '人员'
                        : `组织 ${c.aggregate_id}`,
                  },
                  {
                    title: '接受结果',
                    render: (_, c) => (
                      <Tag>
                        {c.outcome === PersonnelOutcome.APPLIED
                          ? '已应用'
                          : '过期版本 · 当前事实未变'}
                      </Tag>
                    ),
                  },
                  {
                    title: '前后状态',
                    render: (_, c) =>
                      `${c.before?.facts.status ?? '首次出现，无前值'} → ${c.after.facts.status}`,
                  },
                  {
                    title: '操作',
                    render: (_, c) => (
                      <Button
                        type="link"
                        onClick={() => setSelectedChange(`${c.source_id}/${c.partition_sequence}`)}
                      >
                        查看变更证据
                      </Button>
                    ),
                  },
                ]}
              />
              <Space wrap style={{ marginTop: 12 }}>
                {changeCursor && (
                  <Button onClick={() => nextPage('change_cursor')}>变更首页</Button>
                )}
                {report.next_change_cursor && (
                  <Button onClick={() => nextPage('change_cursor', report.next_change_cursor!)}>
                    下一页变更
                  </Button>
                )}
              </Space>
            </Card>
            <Card title="当前与历史授权来源">
              <Typography.Paragraph>
                每行保留完整角色、范围和来源；多个来源不能合并成一份访问路径。旧代际个人来源不会随重新加入继承，曾经属于组不表示现在ALLOW。
              </Typography.Paragraph>
              <Table<PersonnelGrantSource>
                rowSelection={{
                  selectedRowKeys: reviewSelection,
                  onChange: (keys) => setReviewSelection(keys.map(String)),
                  preserveSelectedRowKeys: false,
                }}
                rowKey={(s) => s.grant.grant_id}
                dataSource={report.sources}
                pagination={false}
                scroll={{ x: 900 }}
                locale={{ emptyText: '当前应用没有该人员的授权来源' }}
                columns={[
                  {
                    title: '固定角色',
                    render: (_, s) => `${s.grant.role_code} · v${s.grant.role_version}`,
                  },
                  { title: '来源类型', dataIndex: ['grant', 'source_type'] },
                  {
                    title: '当前 / 历史关系',
                    render: (_, s) =>
                      s.grant.group_id
                        ? '动态组 · 需核对当前关系'
                        : s.current_generation
                          ? '当前成员代际'
                          : '旧代际 · 仅保留历史',
                  },
                  {
                    title: '来源状态',
                    render: (_, s) => <PermissionStatus state={s.grant.effective_state} />,
                  },
                  {
                    title: '操作',
                    render: (_, s) => (
                      <Button type="link" onClick={() => setSelectedSource(s.grant.grant_id)}>
                        查看完整来源
                      </Button>
                    ),
                  },
                ]}
              />
              <ReviewCreate
                report={report}
                selected={reviewSelection}
                sourceCursor={sourceCursor}
              />
              <Space wrap style={{ marginTop: 12 }}>
                {sourceCursor && (
                  <Button onClick={() => nextPage('source_cursor')}>来源首页</Button>
                )}
                {report.next_source_cursor && (
                  <Button onClick={() => nextPage('source_cursor', report.next_source_cursor!)}>
                    下一页来源
                  </Button>
                )}
              </Space>
            </Card>
          </>
        )
      )}
      <GovernanceModal
        title="不可变变更证据"
        open={!!change}
        onCancel={() => setSelectedChange('')}
        width={760}
      >
        {change && (
          <>
            <Descriptions
              column={1}
              items={[
                { label: '原事件', children: code(change.event_id) },
                {
                  label: '来源 / 序号',
                  children: `${change.source_id} / ${change.partition_sequence}`,
                },
                { label: '事件摘要', children: code(change.event_fingerprint) },
                { label: '源端发生时间', children: change.occurred_at },
              ]}
            />
            <section className="g-detail-section">
              <h3>接受前事实</h3>
              {change.before ? (
                <Facts value={change.before.facts} member={change.before.member} />
              ) : (
                <p>首次出现，未记录前值。</p>
              )}
            </section>
            <section className="g-detail-section">
              <h3>接受后事实</h3>
              <Facts value={change.after.facts} member={change.after.member} />
            </section>
            <Alert
              type="info"
              message="此证据描述当次接受结果，不能替代当前人员状态或业务访问核验。"
            />
          </>
        )}
      </GovernanceModal>
      <GovernanceModal
        title="人员关联的完整来源"
        open={!!source}
        onCancel={() => setSelectedSource('')}
        width={760}
      >
        {source && (
          <>
            <PermissionDetails value={source.grant} />
            {!!source.group_relations.length && (
              <section className="g-detail-section">
                <h3>当前与历史组关系</h3>
                {source.group_relations.map((g) => (
                  <div key={g.id}>
                    <p>
                      第 {g.generation} 代 · {g.current ? '当前关系' : '历史关系'} ·{' '}
                      {g.group_active ? '组织有效' : '组织不可用'}
                    </p>
                    <Assignments value={JSON.parse(g.periods_json)} />
                  </div>
                ))}
                <Alert
                  type="info"
                  message="关系、代际、任职时段和当前人员状态共同影响组资格；历史关系不是ALLOW。"
                />
              </section>
            )}
            <section className="g-detail-section">
              <h3>真实撤权回执</h3>
              <p>
                {source.revocation_receipt?.status === ReceiptState.COMPLETED
                  ? '本来源撤权已实际确认'
                  : source.revocation_receipt?.status === ReceiptState.BLOCKED
                    ? '回执投影阻断，需恢复处理'
                    : source.revocation_receipt
                      ? '尚无完成撤权证明'
                      : '当前来源无严格撤权回执'}
              </p>
              {source.revocation_receipt?.operation_id &&
                code(source.revocation_receipt.operation_id)}
            </section>
            <Link
              to={`/governance/diagnostic?${applicationSearch(params, partition.application_id, partition.environment)}&grant=${encodeURIComponent(source.grant.grant_id)}`}
            >
              前往原来源诊断与受控回收
            </Link>
          </>
        )}
      </GovernanceModal>
    </>
  );
}

function Facts({
  value,
  member,
}: {
  value: PersonnelFacts;
  member?: { status: string; generation: number; version: number } | null;
}) {
  return (
    <>
      <p>
        源端状态：{value.status}
        {value.parent_id !== null ? ` · 上级组织 ${value.parent_id}` : ''}
      </p>
      {member && (
        <p>
          当次企业成员：{memberLabels[member.status]} · 第 {member.generation} 代 · 版本{' '}
          {member.version}
        </p>
      )}
      <Assignments value={value.assignments} />
    </>
  );
}

function Assignments({ value }: { value: PersonnelAssignment[] }) {
  const labels: Record<string, string> = {
    PRIMARY: '主岗',
    CONCURRENT: '兼岗',
    DOTTED: '协作任职',
  };
  return (
    <>
      {value.map((a) => (
        <p key={a.id}>
          任职 {a.id} · 组织 {a.org_id} · {labels[a.type]}
          {a.leader ? ' · 负责人' : ''} · {a.valid_from} → {a.valid_to ?? '未设置截止'}
        </p>
      ))}
    </>
  );
}
