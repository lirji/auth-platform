import { GovernanceModal, GovernanceEmpty } from '../governance/presentation';
import { Link, useSearchParams } from 'react-router-dom';
import { applicationSearch } from '../governance/context';
import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Form,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Typography,
} from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  accessState,
  managedPolicies,
  management,
  members,
  registerPolicy,
  publishedCatalog,
  type PublishedCatalog,
  type PolicyConfiguration,
  type Role,
} from '../api/governance';
import { useGovernanceContext } from './GovernancePage';
import { PolicySummary } from './GovernanceRequestsPage';
import { ScopeFields, scopeRule, type ScopeInput } from '../governance/ScopeFields';
import { Failure } from '../governance/feedback';
import { useCommand } from '../governance/useCommand';
import { useCatalogSelection } from '../governance/useCatalogSelection';
import { roleScopeEligibility } from '../governance/publishedCatalog';

/** 策略配置受当前应用委派保护，审批人资格和授权上限在服务端再次核对。 */
export default function GovernancePoliciesPage() {
  const { partition, queryKey } = useGovernanceContext();
  const [params, setParams] = useSearchParams();
  const after = params.get('policy_after') ?? undefined;
  const setAfter = (value?: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set('policy_after', value);
    else next.delete('policy_after');
    setParams(next);
  };
  const [open, setOpen] = useState(false);
  const [detail, setDetail] = useState<PolicyConfiguration>();
  const qc = useQueryClient();
  const authority = useQuery({
    queryKey: [...queryKey, 'management'],
    queryFn: () => management(partition),
    retry: false,
  });
  const catalog = useQuery({
    queryKey: [...queryKey, 'published-catalog'],
    queryFn: ({ signal }) => publishedCatalog(partition, signal),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });
  const list = useQuery({
    queryKey: [...queryKey, 'managed-policies', after],
    queryFn: () => managedPolicies(partition, after),
    retry: false,
  });
  const refresh = () => {
    void qc.invalidateQueries({ queryKey });
  };
  if ((authority.error || list.error) && !open)
    return <Failure error={authority.error ?? list.error} retry={refresh} />;
  return (
    <>
      {(authority.error || list.error) && (
        <Failure error={authority.error ?? list.error} retry={refresh} />
      )}
      <Card
        title="策略目录"
        loading={authority.isPending}
        extra={
          <Space>
            <Button onClick={refresh}>刷新策略</Button>
            <Button
              type="primary"
              disabled={!catalog.data || catalog.isFetching || !!catalog.error}
              onClick={() => setOpen(true)}
            >
              创建申请策略
            </Button>
          </Space>
        }
      >
        {catalog.error && <Failure error={catalog.error} retry={() => void catalog.refetch()} />}
        <Alert
          type="info"
          showIcon
          message="策略固定角色版本、范围、期限和审批成员。新策略不修改已提交申请。"
          style={{ marginBottom: 16 }}
        />
        <Table<PolicyConfiguration>
          rowKey={(r) => r.policy.id}
          locale={{
            emptyText: (
              <GovernanceEmpty
                title="暂无申请策略"
                description="登记策略后，成员才能申请对应角色与固定范围。"
              />
            ),
          }}
          dataSource={list.data?.items}
          loading={list.isPending}
          pagination={false}
          scroll={{ x: 780 }}
          columns={[
            {
              title: '固定角色',
              render: (_, r) => (
                <Link
                  to={`/governance/roles?${applicationSearch(params, partition.application_id, partition.environment)}&role=${encodeURIComponent(r.policy.role_id)}`}
                >
                  {r.policy.role_code} · v{r.policy.role_version}
                </Link>
              ),
            },
            { title: '策略版本', render: (_, r) => r.policy.policy_version },
            {
              title: '上限',
              render: (_, r) => `${Math.floor(r.policy.max_duration_seconds / 60)} 分钟`,
            },
            { title: '配置状态', render: (_, r) => (r.enabled ? '已登记（提交时重验）' : '停用') },
            {
              title: '操作',
              render: (_, r) => (
                <Button type="link" onClick={() => setDetail(r)}>
                  策略详情
                </Button>
              ),
            },
          ]}
        />
        <Space style={{ marginTop: 12 }}>
          {after && <Button onClick={() => setAfter(undefined)}>策略首页</Button>}
          {list.data?.next_cursor && (
            <Button onClick={() => setAfter(list.data!.next_cursor!)}>下一页策略</Button>
          )}
        </Space>
      </Card>
      <GovernanceModal
        title="固定申请策略"
        open={!!detail}
        onCancel={() => setDetail(undefined)}
        width={720}
      >
        {detail && (
          <>
            <PolicySummary policy={detail.policy} />
            <Typography.Paragraph>
              审批成员：{detail.approver_membership_id} · 第 {detail.approver_generation} 代
            </Typography.Paragraph>
          </>
        )}
      </GovernanceModal>
      {open && catalog.data && (
        <PolicyForm
          catalog={catalog.data}
          close={() => setOpen(false)}
          saved={() => {
            refresh();
            setOpen(false);
          }}
        />
      )}
    </>
  );
}

function PolicyForm({
  catalog,
  close,
  saved,
}: {
  catalog: PublishedCatalog;
  close: () => void;
  saved: () => void;
}) {
  const { partition, queryKey } = useGovernanceContext();
  const [form] = Form.useForm();
  const [dirty, setDirty] = useState(false);
  const [roleAfter, setRoleAfter] = useState<string>();
  const [memberAfter, setMemberAfter] = useState<string>();
  const roles = useQuery({
    queryKey: [...queryKey, 'policy-roles', roleAfter],
    queryFn: () => accessState(partition, roleAfter),
    retry: false,
  });
  const people = useQuery({
    queryKey: [...queryKey, 'policy-members', memberAfter],
    queryFn: () => members(partition, memberAfter),
    retry: false,
  });
  const roleId = Form.useWatch('role_id', form);
  const role: Role | undefined = roles.data?.roles.find((r) => r.id === roleId);
  const command = useCommand(registerPolicy);
  const selection = useCatalogSelection(catalog, partition, command.busy || command.unknown);
  const eligibility = roleScopeEligibility(role, selection.catalog);
  const cancel = () => {
    if (command.busy || command.unknown) return;
    if (dirty)
      Modal.confirm({
        title: '放弃未提交的策略？',
        okText: '放弃编辑',
        cancelText: '继续编辑',
        onOk: close,
      });
    else close();
  };
  const finish = async (
    values: ScopeInput & {
      role_id: string;
      member: string;
      minutes: number;
      policy_version: number;
    },
  ) => {
    if (!command.unknown && !(await selection.verify())) return;
    const response = await command.send((id) => {
      const member = people.data!.items.find((m) => m.membership_id === values.member)!;
      return {
        ...partition,
        command_id: id,
        role_id: values.role_id,
        scope_rule: scopeRule(values),
        max_duration_seconds: values.minutes * 60,
        approver_membership_id: member.membership_id,
        approver_generation: member.generation,
        policy_version: values.policy_version,
      };
    });
    if (response) saved();
  };
  return (
    <GovernanceModal
      title="创建固定申请策略"
      footer={
        <Button
          type="primary"
          loading={command.busy || selection.checking}
          disabled={
            !command.unknown &&
            (selection.changed ||
              !!eligibility.reason ||
              roles.isPending ||
              people.isPending ||
              !!roles.error ||
              !!people.error)
          }
          onClick={() => (command.unknown ? void finish(form.getFieldsValue()) : form.submit())}
        >
          {command.unknown ? '重试原策略' : '登记固定策略'}
        </Button>
      }
      open
      onCancel={cancel}
      width={720}
      maskClosable={false}
      closeDisabled={command.busy || command.unknown}
    >
      {selection.notice}
      {(roles.error || people.error) && !command.unknown && !command.busy ? (
        <Failure
          error={roles.error ?? people.error}
          retry={() => {
            void roles.refetch();
            void people.refetch();
          }}
        />
      ) : (
        <>
          {!!command.error && <Failure error={command.error} />}
          {command.unknown && <Alert type="warning" message="策略创建结果未确认，请原样重试。" />}
          <Form
            form={form}
            layout="vertical"
            initialValues={{
              minutes: Math.min(30, Math.floor(selection.catalog.max_duration_seconds / 60)),
              policy_version: 1,
            }}
            onValuesChange={() => setDirty(true)}
            onFinish={finish}
            disabled={command.busy || command.unknown || selection.checking}
          >
            <Form.Item name="role_id" label="固定角色版本" rules={[{ required: true }]}>
              <Select
                aria-label="策略固定角色版本"
                options={roles.data?.roles.map((r) => ({
                  value: r.id,
                  label: `${r.role_code} · v${r.version}${roleScopeEligibility(r, selection.catalog).reason ? `（${roleScopeEligibility(r, selection.catalog).reason}）` : ''}`,
                  disabled: !!roleScopeEligibility(r, selection.catalog).reason,
                }))}
                onChange={() => form.resetFields(['resource_type', 'scope_kind', 'scope_values'])}
              />
            </Form.Item>
            <Space style={{ marginBottom: 12 }}>
              {roleAfter && (
                <Button
                  onClick={() => {
                    setRoleAfter(undefined);
                    form.resetFields(['role_id', 'resource_type', 'scope_kind', 'scope_values']);
                  }}
                >
                  角色首页
                </Button>
              )}
              {roles.data?.next_role_cursor && (
                <Button
                  onClick={() => {
                    setRoleAfter(roles.data!.next_role_cursor!);
                    form.resetFields(['role_id', 'resource_type', 'scope_kind', 'scope_values']);
                  }}
                >
                  下一页角色
                </Button>
              )}
            </Space>
            {role && eligibility.reason && (
              <Alert type="warning" message={eligibility.reason} style={{ marginBottom: 12 }} />
            )}
            <ScopeFields resourceTypes={eligibility.resourceTypes} />
            <Form.Item
              name="member"
              label="审批成员"
              extra="指定成员仍须有覆盖本策略能力和期限的管理委派；申请人与审批人不能相同。"
              rules={[{ required: true }]}
            >
              <Select
                aria-label="审批成员"
                options={people.data?.items.map((m) => ({
                  value: m.membership_id,
                  label: `${m.member_kind} · ${m.membership_id} · 第${m.generation}代`,
                }))}
              />
            </Form.Item>
            <Space style={{ marginBottom: 12 }}>
              {memberAfter && (
                <Button
                  onClick={() => {
                    setMemberAfter(undefined);
                    form.resetFields(['member']);
                  }}
                >
                  成员首页
                </Button>
              )}
              {people.data?.next_cursor && (
                <Button
                  onClick={() => {
                    setMemberAfter(people.data!.next_cursor!);
                    form.resetFields(['member']);
                  }}
                >
                  下一页成员
                </Button>
              )}
            </Space>
            <Form.Item
              name="minutes"
              label="最长授权分钟数"
              rules={[
                { required: true },
                {
                  type: 'number',
                  min: 1,
                  max: Math.floor(selection.catalog.max_duration_seconds / 60),
                },
              ]}
            >
              <InputNumber precision={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              name="policy_version"
              label="策略版本"
              rules={[{ required: true }, { type: 'number', min: 1 }]}
            >
              <InputNumber precision={0} />
            </Form.Item>
          </Form>
        </>
      )}
    </GovernanceModal>
  );
}
