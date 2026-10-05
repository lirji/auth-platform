import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Form,
  Input,
  Select,
  Skeleton,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { actReview, reviewDetail, reviewList, reviewResponsibles } from '../../../api/accessReview';
import {
  ReviewActionType,
  canReviewDecide,
  ReviewDecision,
  ReviewItemState,
  ReviewTaskState,
  type ReviewAction,
  type ReviewActionKind,
  type ReviewDecisionCode,
  type ReviewDetail,
  type ReviewItem,
  type ReviewSummary,
} from '../model/accessReview';
import { GrantSourceType } from '../../governance/shared/codes';
import { applicationSearch } from '../../governance/shared/context';
import { useCommand } from '../../governance/shared/useCommand';
import { Failure } from '../../governance/shared/feedback';
import {
  GovernanceEmpty,
  GovernanceModal,
  PermissionStatus,
} from '../../governance/shared/presentation';
import { useGovernanceContext } from '../../governance/shell/GovernancePage';
import { PermissionDetails } from '../../permissions/pages/GovernancePermissionsPage';

const labels: Record<string, string> = {
  RUNNING: '复核进行中',
  NEEDS_INVESTIGATION: '仍需调查',
  COMPLETED: '复核已完成',
  CANCELLED: '已取消未处理项',
  PENDING: '待决定',
  INVESTIGATE: '需调查',
  KEPT: '已记录保留',
  WAIT_REVOKE: '已发撤权，待真实确认',
  REVOKED: '撤权已实际确认',
};
const uuid = (v: string) =>
  /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(v);
const code = (v: string) => (
  <Typography.Text style={{ overflowWrap: 'anywhere' }} copyable>
    {v}
  </Typography.Text>
);

/** 人工任务入口与详情复用真实固定分区，关闭页面不会丢失已提交决定。 */
export default function GovernanceReviewsPage() {
  const { partition, queryKey } = useGovernanceContext(),
    [params, setParams] = useSearchParams();
  const id = params.get('review') ?? '',
    after = params.get('review_after') ?? undefined;
  const list = useQuery({
    queryKey: [...queryKey, 'reviews', after],
    queryFn: ({ signal }) => reviewList(partition, undefined, after, signal),
    enabled: !id,
    retry: false,
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });
  const open = (value: string) => {
    const next = applicationSearch(params, partition.application_id, partition.environment);
    if (value) next.set('review', value);
    setParams(next);
  };
  if (id)
    return uuid(id) ? (
      <ReviewTask key={`${queryKey.join(':')}:${id}`} id={id} back={() => open('')} />
    ) : (
      <Alert type="error" message="复核任务编号无效" />
    );
  const data = !list.error && !list.isFetching ? list.data : undefined;
  return (
    <Card
      title="人工复核任务"
      extra={
        <Button loading={list.isFetching} onClick={() => void list.refetch()}>
          刷新任务
        </Button>
      }
    >
      <Typography.Paragraph>
        从人员变更核对页选择具体来源创建复核。这里可以找回固定范围与负责人，继续尚未完成的决定。
      </Typography.Paragraph>
      <Link
        to={`/governance/personnel?${applicationSearch(params, partition.application_id, partition.environment)}`}
      >
        前往人员变更核对
      </Link>
      {list.error ? (
        <Failure error={list.error} retry={() => void list.refetch()} />
      ) : list.isFetching ? (
        <Skeleton active />
      ) : (
        data && (
          <>
            <Table<ReviewSummary>
              rowKey="id"
              dataSource={data.items}
              pagination={false}
              scroll={{ x: 800 }}
              locale={{
                emptyText: (
                  <GovernanceEmpty
                    title="暂无人工复核任务"
                    description="显式选择人员、来源和负责人后创建。"
                  />
                ),
              }}
              columns={[
                { title: '任务', render: (_, t) => code(t.id) },
                { title: '目标成员', render: (_, t) => code(t.membership_id) },
                { title: '状态', render: (_, t) => <Tag>{labels[t.state]}</Tag> },
                { title: '更新时间', dataIndex: 'updated_at' },
                {
                  title: '操作',
                  render: (_, t) => (
                    <Button type="link" onClick={() => open(t.id)}>
                      查看复核
                    </Button>
                  ),
                },
              ]}
            />
            <Space wrap style={{ marginTop: 12 }}>
              {after && (
                <Button
                  onClick={() => {
                    const next = new URLSearchParams(params);
                    next.delete('review_after');
                    setParams(next);
                  }}
                >
                  任务首页
                </Button>
              )}
              {data.next_cursor && (
                <Button
                  onClick={() => {
                    const next = new URLSearchParams(params);
                    next.set('review_after', data.next_cursor!);
                    setParams(next);
                  }}
                >
                  下一页任务
                </Button>
              )}
            </Space>
          </>
        )
      )}
    </Card>
  );
}

function ReviewTask({ id, back }: { id: string; back: () => void }) {
  const { partition, queryKey } = useGovernanceContext(),
    [params] = useSearchParams(),
    qc = useQueryClient();
  const key = [...queryKey, 'review', id];
  const query = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => reviewDetail(partition, id, signal),
    retry: false,
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });
  const detail = !query.error && !query.isFetching ? query.data : undefined;
  const [source, setSource] = useState(''),
    [action, setAction] = useState<{ kind: ReviewActionKind; item?: ReviewItem }>();
  const [form] = Form.useForm<{
    decision: ReviewDecisionCode;
    reason: string;
    whole_group_ack: boolean;
    responsible: string;
  }>();
  const decision = Form.useWatch('decision', form);
  const candidates = useQuery({
    queryKey: [...queryKey, 'review-responsibles'],
    queryFn: ({ signal }) => reviewResponsibles(partition, signal),
    enabled: action?.kind === ReviewActionType.RESPONSIBLE,
    retry: false,
    gcTime: 0,
    staleTime: 0,
    refetchOnWindowFocus: false,
  });
  const command = useCommand<{ kind: ReviewActionKind; body: ReviewAction }, ReviewDetail>((v) =>
      actReview(id, v.kind, v.body),
    ),
    frozen = command.busy || command.unknown;
  const start = (kind: ReviewActionKind, item?: ReviewItem) => {
    command.reset();
    form.resetFields();
    setAction({ kind, item });
  };
  const refresh = () => {
    if (!frozen) {
      setSource('');
      setAction(undefined);
      command.reset();
      void query.refetch();
    }
  };
  const submit = async () => {
    if (!action || !detail) return;
    let values = {
      reason: '',
      decision: ReviewDecision.KEEP as ReviewDecisionCode,
      responsible: '',
      whole_group_ack: false,
    };
    if (!command.unknown) {
      // Ant表单已经展示字段错误；校验拒绝属于输入恢复，不能形成未处理Promise页面错误。
      try {
        values = await form.validateFields();
      } catch (failure) {
        if (failure && typeof failure === 'object' && 'errorFields' in failure) return;
        throw failure;
      }
    }
    const result = await command.send((command_id) => {
      const body: ReviewAction = {
        ...partition,
        command_id,
        expected_task_version: detail.version,
        reason: values.reason.trim(),
      };
      if (action.item) {
        body.item_id = action.item.id;
        body.expected_item_version = action.item.version;
      }
      if (action.kind === ReviewActionType.DECISION) {
        body.current_basis_hash = detail.current_report.basis_hash;
        body.decision = values.decision;
        body.whole_group_ack = values.whole_group_ack === true;
      }
      if (action.kind === ReviewActionType.RESPONSIBLE) {
        const [member, generation] = values.responsible.split(':');
        body.responsible_membership_id = member;
        body.responsible_generation = Number(generation);
      }
      return { kind: action.kind, body };
    });
    if (result) {
      qc.setQueryData(key, result);
      setAction(undefined);
      command.reset();
      void qc.invalidateQueries({ queryKey: [...queryKey, 'reviews'] });
    }
  };
  const selected = detail?.items.find((i) => i.id === source),
    terminal =
      detail?.state === ReviewTaskState.COMPLETED || detail?.state === ReviewTaskState.CANCELLED;
  const personnelUrl = detail
    ? `/governance/personnel?${applicationSearch(params, partition.application_id, partition.environment)}&member=${encodeURIComponent(detail.membership_id)}`
    : '';
  return (
    <>
      <Space wrap style={{ marginBottom: 16 }}>
        <Button onClick={back} disabled={frozen}>
          返回复核列表
        </Button>
        <Button onClick={refresh} loading={query.isFetching} disabled={frozen}>
          重新读取复核依据
        </Button>
      </Space>
      {query.error ? (
        <Failure error={query.error} retry={refresh} />
      ) : query.isFetching ? (
        <Card>
          <Skeleton active />
        </Card>
      ) : (
        detail && (
          <>
            <Card title="固定复核范围与负责人">
              <Descriptions
                column={1}
                items={[
                  { label: '任务编号', children: code(detail.id) },
                  { label: '目标成员', children: code(detail.membership_id) },
                  {
                    label: '固定范围',
                    children: `第 ${detail.target_generation} 代 · 显式选择 ${detail.items.length} 条来源`,
                  },
                  { label: '创建原因', children: detail.reason },
                  {
                    label: '负责人',
                    children: (
                      <>
                        {code(detail.responsible_membership_id)} · 第{' '}
                        {detail.responsible_generation} 代
                      </>
                    ),
                  },
                  {
                    label: '任务状态 / 版本',
                    children: (
                      <>
                        <Tag>{labels[detail.state]}</Tag>
                        {detail.version}
                      </>
                    ),
                  },
                  { label: '原依据', children: code(detail.original_basis_hash) },
                  { label: '当前依据', children: code(detail.current_report.basis_hash) },
                ]}
              />
              {!detail.responsible_qualified && (
                <Alert
                  type="warning"
                  message="负责人当前资格已不可用"
                  description="当前合格管理员可显式改派；原责任与已提交决定继续保留。"
                />
              )}
              {detail.target_generation !== detail.current_report.target.generation && (
                <Alert
                  type="warning"
                  message="目标已进入新代际，不能继续原任务的新决定"
                  description="请重新核对并创建新任务；已发撤权仍可按真实回执确认。"
                />
              )}
              <Space wrap style={{ marginTop: 16 }}>
                <Link to={personnelUrl}>查看当前人员与全部分页来源</Link>
                {detail.state !== ReviewTaskState.COMPLETED && (
                  <Button onClick={() => start(ReviewActionType.RESPONSIBLE)}>
                    重新指定负责人
                  </Button>
                )}
                {!terminal && (
                  <Button danger onClick={() => start(ReviewActionType.CANCEL)}>
                    取消未处理项
                  </Button>
                )}
              </Space>
            </Card>
            <Card title="所选来源与逐项决定">
              <Typography.Paragraph>
                保留意见不会恢复或延期来源。撤权已发与真实确认分别记录，其他来源仍须独立核对。
              </Typography.Paragraph>
              <Table<ReviewItem>
                rowKey="id"
                dataSource={detail.items}
                pagination={false}
                scroll={{ x: 900 }}
                columns={[
                  {
                    title: '角色 / 来源',
                    render: (_, i) => (
                      <>
                        {i.original.grant.role_code} v{i.original.grant.role_version}
                        <br />
                        {i.original.grant.source_type}
                      </>
                    ),
                  },
                  { title: '复核状态', render: (_, i) => <Tag>{labels[i.state]}</Tag> },
                  { title: '决定原因', dataIndex: 'reason', render: (v) => v ?? '尚未决定' },
                  {
                    title: '操作',
                    render: (_, i) => (
                      <Space wrap>
                        <Button type="link" onClick={() => setSource(i.id)}>
                          查看固定来源
                        </Button>
                        {canReviewDecide(detail, i) && (
                          <Button onClick={() => start(ReviewActionType.DECISION, i)}>
                            记录决定
                          </Button>
                        )}
                        {detail.can_decide && i.state === ReviewItemState.WAIT_REVOKE && (
                          <Button onClick={() => start(ReviewActionType.CONFIRM, i)}>
                            确认实际撤权
                          </Button>
                        )}
                      </Space>
                    ),
                  },
                ]}
              />
            </Card>
            <Card title="当前其他来源仍需分别核对">
              <Typography.Paragraph>
                以下是当前来源第一页，包含已选与未选来源，不能据撤销一项推断全部权限消失。
              </Typography.Paragraph>
              <Table
                rowKey={(s) => s.grant.grant_id}
                dataSource={detail.current_report.sources}
                pagination={false}
                scroll={{ x: 700 }}
                columns={[
                  {
                    title: '角色',
                    render: (_, s) => `${s.grant.role_code} v${s.grant.role_version}`,
                  },
                  { title: '来源', render: (_, s) => s.grant.source_type },
                  {
                    title: '当前状态',
                    render: (_, s) => <PermissionStatus state={s.grant.effective_state} />,
                  },
                  { title: '来源编号', render: (_, s) => code(s.grant.grant_id) },
                ]}
              />
              <Link to={personnelUrl}>前往人员页查看完整范围与下一页</Link>
            </Card>
          </>
        )
      )}
      <GovernanceModal
        title="复核固定来源"
        open={!!selected}
        onCancel={() => setSource('')}
        width={760}
      >
        {selected && (
          <>
            <PermissionDetails value={selected.original.grant} />
            <section className="g-detail-section">
              <h3>复核决定与真实完成证明</h3>
              <p>
                {labels[selected.state]} · {selected.reason ?? '尚无决定'}
              </p>
              {selected.confirmed_operation_id && code(selected.confirmed_operation_id)}
              <Alert
                type="info"
                message="上方为创建时完整来源，当前有效性见当前人员来源诊断。保留意见不会恢复历史授权。"
              />
            </section>
            <Link
              to={`/governance/diagnostic?${applicationSearch(params, partition.application_id, partition.environment)}&grant=${encodeURIComponent(selected.grant_id)}`}
            >
              查看该来源当前诊断
            </Link>
          </>
        )}
      </GovernanceModal>
      <GovernanceModal
        title={
          action?.kind === ReviewActionType.CANCEL
            ? '取消未处理复核项'
            : action?.kind === ReviewActionType.RESPONSIBLE
              ? '重新指定复核负责人'
              : action?.kind === ReviewActionType.CONFIRM
                ? '确认实际撤权结果'
                : '记录来源复核决定'
        }
        open={!!action && !!detail}
        closeDisabled={frozen}
        onCancel={() => {
          if (!frozen) setAction(undefined);
        }}
        footer={
          <Button
            type="primary"
            loading={command.busy}
            onClick={() => void submit()}
            disabled={
              action?.kind === ReviewActionType.RESPONSIBLE &&
              !command.unknown &&
              (!!candidates.error || candidates.isFetching || !candidates.data?.length)
            }
          >
            {command.unknown ? '重试原复核命令' : '提交本次记录'}
          </Button>
        }
      >
        {action?.kind === ReviewActionType.CANCEL && (
          <Alert
            type="warning"
            message="只取消未处理项，已撤权不会恢复"
            description="已发撤权意图与决定保留；后续仍可确认原实际撤权结果。"
          />
        )}
        {action?.kind === ReviewActionType.CONFIRM && (
          <Alert
            type="info"
            message="只有匹配原撤权的真实完成回执才能确认"
            description="仍处理中或投影阻断时保留原检查点，请先恢复投影再重试确认。"
          />
        )}
        <Form form={form} layout="vertical" disabled={frozen} style={{ marginTop: 16 }}>
          {action?.kind === ReviewActionType.DECISION && (
            <>
              <Form.Item
                name="decision"
                label="复核决定"
                rules={[{ required: true, message: '请选择本来源的决定' }]}
              >
                <Select
                  placeholder="选择决定"
                  options={[
                    { value: ReviewDecision.KEEP, label: '保留意见（不恢复或延期来源）' },
                    { value: ReviewDecision.REVOKE, label: '撤销本来源（等待真实回执）' },
                    { value: ReviewDecision.INVESTIGATE, label: '需调查（复核仍未完成）' },
                  ]}
                />
              </Form.Item>
              {decision === ReviewDecision.REVOKE &&
                action.item?.original.grant.source_type === GrantSourceType.GROUP && (
                  <>
                    <Alert type="warning" message="此操作撤销整个组来源，影响全组受益人" />
                    <Form.Item
                      name="whole_group_ack"
                      valuePropName="checked"
                      rules={[
                        {
                          validator: (_, v) =>
                            v ? Promise.resolve() : Promise.reject(new Error('请明确确认全组影响')),
                        },
                      ]}
                    >
                      <Checkbox>我已核对并确认撤销整个组来源</Checkbox>
                    </Form.Item>
                  </>
                )}
            </>
          )}
          {action?.kind === ReviewActionType.RESPONSIBLE && (
            <>
              {candidates.error ? (
                <Failure error={candidates.error} retry={() => void candidates.refetch()} />
              ) : (
                <Form.Item
                  name="responsible"
                  label="当前合格负责人"
                  rules={[{ required: true, message: '请选择当前有管理与独立诊断资格的负责人' }]}
                >
                  <Select
                    loading={candidates.isFetching}
                    placeholder="显式选择负责人"
                    options={candidates.data?.map((c) => ({
                      value: `${c.membership_id}:${c.generation}`,
                      label: `${c.membership_id} · 第 ${c.generation} 代`,
                    }))}
                  />
                </Form.Item>
              )}
            </>
          )}
          <Form.Item
            name="reason"
            label="记录原因"
            rules={[{ required: true, whitespace: true, max: 500, message: '填写1–500字原因' }]}
          >
            <Input.TextArea rows={3} maxLength={500} showCount />
          </Form.Item>
        </Form>
        {!!command.error && <Failure error={command.error} />}
        {command.unknown && (
          <Alert
            type="warning"
            message="结果尚未确认，请重试原命令"
            description="当前任务、条目版本、依据和原因保持原输入，不能重新生成操作命令。"
          />
        )}
      </GovernanceModal>
    </>
  );
}
