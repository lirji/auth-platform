import { RoleMigrationPanel } from '../../roles/components/RoleMigrationPanel';
import { menuTitle } from '../../catalog/model/catalogModel';
import {
  GovernanceModal,
  CapabilityList,
  GovernanceEmpty,
} from '../../governance/shared/presentation';
import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Skeleton,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  accessState,
  createRole,
  grantScoped,
  management,
  members,
  roleImpact,
  publishedCatalog,
  type PublishedCatalog,
  type Grant,
  type Role,
} from '../../../api/governance';
import { useGovernanceContext } from '../../governance/shell/GovernancePage';
import { Failure } from '../../governance/shared/feedback';
import { useCommand } from '../../governance/shared/useCommand';
import { ProjectionState } from '../../governance/shared/codes';
import { CatalogEditor } from '../../catalog/components/CatalogEditor';
import { ScopeFields, scopeRule, type ScopeInput } from '../components/ScopeFields';
import {
  PublishedCatalogBrowser,
  PublishedCatalogSelector,
} from '../../catalog/components/PublishedCatalogSelector';
import {
  capabilitySelectionError,
  roleScopeEligibility,
} from '../../catalog/model/publishedCatalog';
import { useCatalogSelection } from '../../catalog/components/useCatalogSelection';
import { useRoleDirectory } from '../../governance/shared/useRoleDirectory';
import { applicationSearch } from '../../governance/shared/context';

const projectionLabels: Record<string, string> = {
  READY: '已同步',
  UPDATING: '同步中',
  BLOCKED: '同步失败，等待恢复',
  LEGACY: '旧版投影',
};
const grantLabels: Record<string, string> = {
  PENDING: '待生效',
  ACTIVE: '图已确认',
  REVOKED: '已撤销',
};

/** 独立页面与旧标签共享原命令用例；读取目录不会自动授予业务权限。 */
export default function GovernanceAccessPage({ view }: { view?: 'roles' | 'grants' }) {
  const { partition, queryKey } = useGovernanceContext();
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const cursors = {
    role: params.get('role_after') ?? undefined,
    grant: params.get('grant_after') ?? undefined,
  };
  const setParam = (key: string, value?: string) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: key === 'q' });
  };
  const link = (path: string, key?: string, value?: string) => {
    const next = applicationSearch(params, partition.application_id, partition.environment);
    if (key && value) next.set(key, value);
    return `/governance/${path}?${next}`;
  };
  const [roleOpen, setRoleOpen] = useState(false);
  const [grantOpen, setGrantOpen] = useState(false);
  const [catalogOpen, setCatalogOpen] = useState(false);
  const [copy, setCopy] = useState<Role>();
  const [migrationRole, setMigrationRole] = useState<Role>();
  const migrationTrigger = useRef<HTMLElement | null>(null);
  // 该子弹层关闭后直接卸载，显式恢复入口焦点，避免依赖未执行的关闭动画回调。
  const closeMigration = () => {
    setMigrationRole(undefined);
    requestAnimationFrame(() => {
      if (migrationTrigger.current?.isConnected) migrationTrigger.current.focus();
    });
  };
  const migrationContext = queryKey.join(':');
  useEffect(() => setMigrationRole(undefined), [migrationContext]);
  const authority = useQuery({
    queryKey: [...queryKey, 'management'],
    queryFn: () => management(partition),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });
  const catalog = useQuery({
    queryKey: [...queryKey, 'published-catalog'],
    queryFn: ({ signal }) => publishedCatalog(partition, signal),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });
  const result = useQuery({
    queryKey: [...queryKey, 'access', cursors],
    queryFn: ({ signal }) => accessState(partition, cursors.role, cursors.grant, signal),
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });
  const roleDirectory = useRoleDirectory();
  const selectedId = params.get('role') ?? undefined;
  const selected = !roleDirectory.error
    ? roleDirectory.data?.find((role) => role.id === selectedId)
    : undefined;
  const impact = useQuery({
    queryKey: [...queryKey, 'impact', selected?.id],
    queryFn: () => roleImpact(partition, selected!.id),
    enabled: !!selected,
    retry: false,
    gcTime: 0,
  });
  const refresh = () => {
    void qc.invalidateQueries({ queryKey });
  };
  const q = (params.get('q') ?? '').trim().toLowerCase();
  const roleName = (id: string) =>
    !roleDirectory.error ? roleDirectory.data?.find((role) => role.id === id) : undefined;
  const filteredRoles = result.data?.roles.filter(
    (role) =>
      (!q ||
        `${role.role_code} ${role.version} ${role.capabilities.join(' ')}`
          .toLowerCase()
          .includes(q)) &&
      (!params.get('resource') ||
        (!catalog.error &&
          role.capabilities.some((code) =>
            catalog.data?.capabilities.some(
              (cap) => cap.code === code && cap.resource_type === params.get('resource'),
            ),
          ))),
  );
  const filteredGrants = result.data?.grants.filter(
    (grant) =>
      (!params.get('state') || grant.state === params.get('state')) &&
      (!q ||
        `${grant.member_id} ${roleName(grant.role_id)?.role_code ?? grant.role_id} ${grant.source_id} ${grant.source_type}`
          .toLowerCase()
          .includes(q)),
  );
  if ((authority.error || result.error) && !roleOpen && !grantOpen && !migrationRole)
    return <Failure error={authority.error ?? result.error} retry={refresh} />;
  const roleList = (
    <>
      <div className="g-data-heading">
        <p>固定版本保持不变；筛选仅针对当前游标页，下一页继续读取。</p>
      </div>
      <div className="g-catalog-filters">
        <Input.Search
          aria-label="搜索当前页角色"
          placeholder="搜索当前页角色或权限"
          value={params.get('q') ?? ''}
          onChange={(event) => setParam('q', event.target.value)}
        />
        <Select
          aria-label="筛选角色资源"
          placeholder="全部资源类型"
          allowClear
          value={params.get('resource') ?? undefined}
          disabled={!!catalog.error}
          options={catalog.data?.resource_types.map((type) => ({
            value: type.code,
            label: type.code,
          }))}
          onChange={(value) => setParam('resource', value)}
        />
        <Button
          onClick={() =>
            setParams(applicationSearch(params, partition.application_id, partition.environment))
          }
        >
          重置筛选
        </Button>
      </div>
      <p className="g-result-count">
        当前页匹配 {filteredRoles?.length ?? 0} / {result.data?.roles.length ?? 0} 个角色版本
      </p>
      <Table<Role>
        rowKey="id"
        locale={{
          emptyText: (
            <GovernanceEmpty
              title="没有匹配的角色版本"
              description="调整当前页筛选，或创建固定角色版本。"
            />
          ),
        }}
        dataSource={filteredRoles}
        pagination={false}
        scroll={{ x: 850 }}
        columns={[
          {
            title: '角色',
            dataIndex: 'role_code',
            width: 200,
            render: (code, role) => (
              <Button type="link" className="g-code-link" onClick={() => setParam('role', role.id)}>
                {code}
              </Button>
            ),
          },
          { title: '版本', dataIndex: 'version', width: 70 },
          {
            title: '权限',
            dataIndex: 'capabilities',
            render: (caps: string[]) => <CapabilityList values={caps} compact />,
          },
          {
            title: '操作',
            width: 310,
            render: (_, row) => (
              <Space wrap>
                <Button type="link" onClick={() => setParam('role', row.id)}>
                  角色详情
                </Button>
                <Button
                  type="link"
                  disabled={!catalog.data || catalog.isFetching || !!catalog.error}
                  onClick={() => {
                    setCopy(row);
                    setRoleOpen(true);
                  }}
                >
                  复制新版本
                </Button>
                <Button
                  type="link"
                  disabled={roleDirectory.isFetching || !!roleDirectory.error}
                  onClick={(event) => {
                    migrationTrigger.current = event.currentTarget;
                    setMigrationRole(row);
                  }}
                >
                  迁移预览
                </Button>
              </Space>
            ),
          },
        ]}
      />
      <Space style={{ marginTop: 12 }}>
        {cursors.role && <Button onClick={() => setParam('role_after')}>角色首页</Button>}
        {result.data?.next_role_cursor && (
          <Button onClick={() => setParam('role_after', result.data!.next_role_cursor!)}>
            下一页角色
          </Button>
        )}
      </Space>
    </>
  );
  const grantList = (
    <>
      <div className="g-data-heading">
        <p>每条记录独立保留成员、角色、范围与有效期。筛选仅针对当前游标页。</p>
      </div>
      <div className="g-catalog-filters">
        <Input.Search
          aria-label="搜索当前页授权"
          placeholder="搜索当前页成员、角色或来源"
          value={params.get('q') ?? ''}
          onChange={(event) => setParam('q', event.target.value)}
        />
        <Select
          aria-label="筛选授权状态"
          placeholder="全部授权状态"
          allowClear
          value={params.get('state') ?? undefined}
          options={Object.entries(grantLabels).map(([value, label]) => ({ value, label }))}
          onChange={(value) => setParam('state', value)}
        />
        <Button
          onClick={() =>
            setParams(applicationSearch(params, partition.application_id, partition.environment))
          }
        >
          重置筛选
        </Button>
      </div>
      <p className="g-result-count">
        当前页匹配 {filteredGrants?.length ?? 0} / {result.data?.grants.length ?? 0} 条授权
      </p>
      <Table<Grant>
        rowKey="id"
        locale={{
          emptyText: (
            <GovernanceEmpty
              title="没有匹配的成员授权"
              description="调整筛选或授予成员，再查看实际投影状态。"
            />
          ),
        }}
        dataSource={filteredGrants}
        pagination={false}
        scroll={{ x: 1150 }}
        columns={[
          {
            title: '成员 / 代际',
            width: 230,
            render: (_, row) => (
              <>
                <Typography.Text copyable>{row.member_id}</Typography.Text>
                <div>第 {row.member_generation} 代</div>
              </>
            ),
          },
          {
            title: '固定角色',
            width: 180,
            render: (_, row) => {
              const role = roleName(row.role_id);
              return role ? (
                <Link to={link('roles', 'role', role.id)}>
                  {role.role_code} · v{role.version}
                </Link>
              ) : (
                <span>{row.role_id}</span>
              );
            },
          },
          {
            title: '来源',
            width: 180,
            render: (_, row) => (
              <>
                {row.source_type}
                <div>{row.source_id}</div>
              </>
            ),
          },
          {
            title: '范围',
            dataIndex: 'scope',
            width: 140,
            render: (value) => (value === 'TENANT_ALL' ? '当前企业全部资源' : '范围详情见授权来源'),
          },
          {
            title: '有效期（本地时间）',
            width: 180,
            render: (_, row) => (
              <Space direction="vertical" size={0}>
                <span>{new Date(row.valid_from).toLocaleString('zh-CN', { hour12: false })}</span>
                <span>至 {new Date(row.valid_to).toLocaleString('zh-CN', { hour12: false })}</span>
              </Space>
            ),
          },
          {
            title: '授权记录',
            dataIndex: 'state',
            width: 110,
            render: (value) => (
              <Tag color={value === 'PENDING' ? 'warning' : undefined}>
                {grantLabels[value] ?? value}
              </Tag>
            ),
          },
          {
            title: '操作',
            width: 150,
            render: (_, row) => (
              <Link to={link('diagnostic', 'grant', row.id)}>来源详情 / 撤权</Link>
            ),
          },
        ]}
      />
      <Space style={{ marginTop: 12 }}>
        {cursors.grant && <Button onClick={() => setParam('grant_after')}>授权首页</Button>}
        {result.data?.next_grant_cursor && (
          <Button onClick={() => setParam('grant_after', result.data!.next_grant_cursor!)}>
            下一页授权
          </Button>
        )}
      </Space>
    </>
  );
  return (
    <>
      {(authority.error || result.error) && (
        <Failure error={authority.error ?? result.error} retry={refresh} />
      )}
      <Card
        title={
          view === 'roles' ? '固定角色版本' : view === 'grants' ? '成员授权记录' : '角色与成员授权'
        }
        loading={result.isPending || authority.isPending}
        extra={<Button onClick={refresh}>刷新状态</Button>}
      >
        {authority.data && (
          <Alert
            type={
              authority.data.policy_state === ProjectionState.READY &&
              authority.data.directory_state === ProjectionState.READY
                ? 'info'
                : 'warning'
            }
            showIcon
            message={`权限投影：${projectionLabels[authority.data.policy_state] ?? '未知'} · 成员同步：${projectionLabels[authority.data.directory_state] ?? '未知'}`}
            description="受理不等于生效。角色新版本不会自动迁移旧授权；扩权必须重新授予或审批。"
          />
        )}
        {catalog.error ? (
          <Failure error={catalog.error} retry={() => void catalog.refetch()} />
        ) : (
          catalog.data && <PublishedCatalogBrowser catalog={catalog.data} />
        )}
        {roleDirectory.error && (
          <Failure error={roleDirectory.error} retry={() => void roleDirectory.refetch()} />
        )}
        <div className="g-directory-links">
          <Link to={link('menus')}>菜单权限</Link>
          <Link to={link('catalog')}>权限目录</Link>
          <Link to={link('roles')}>角色管理</Link>
          <Link to={link('grants')}>成员授权</Link>
        </div>
        <Space wrap style={{ marginBottom: 20 }}>
          {view !== 'grants' && (
            <Button
              type="primary"
              disabled={!catalog.data || catalog.isFetching || !!catalog.error}
              onClick={() => {
                setCopy(undefined);
                setRoleOpen(true);
              }}
            >
              创建角色版本
            </Button>
          )}
          <Button
            type={view === 'grants' ? 'primary' : 'default'}
            disabled={
              !catalog.data ||
              catalog.isFetching ||
              !!catalog.error ||
              roleDirectory.isFetching ||
              !!roleDirectory.error
            }
            onClick={() => setGrantOpen(true)}
          >
            授予成员
          </Button>
          {authority.data?.catalog_owner && (
            <Button onClick={() => setCatalogOpen(true)}>应用清单</Button>
          )}
        </Space>
        {view ? (
          view === 'roles' ? (
            roleList
          ) : (
            grantList
          )
        ) : (
          <Tabs
            activeKey={params.get('access_tab') === 'grants' ? 'grants' : 'roles'}
            onChange={(key) => setParam('access_tab', key)}
            items={[
              { key: 'roles', label: '固定角色版本', children: roleList },
              { key: 'grants', label: '成员授权', children: grantList },
            ]}
          />
        )}
      </Card>
      {migrationRole && (
        <RoleMigrationPanel
          key={`${queryKey.join(':')}:${migrationRole.id}`}
          oldRole={migrationRole}
          roles={roleDirectory.data ?? []}
          close={closeMigration}
        />
      )}
      <GovernanceModal
        title={selected ? `${selected.role_code} · 版本 ${selected.version}` : '角色详情'}
        open={!!selectedId}
        onCancel={() => setParam('role')}
        width={760}
      >
        {roleDirectory.error ? (
          <Failure error={roleDirectory.error} retry={() => void roleDirectory.refetch()} />
        ) : roleDirectory.isPending ? (
          <Skeleton active />
        ) : !selected ? (
          <Alert type="warning" message="当前分区未找到该固定角色版本，请刷新后重新选择。" />
        ) : (
          <>
            <Descriptions
              column={1}
              items={[
                { label: '角色编码', children: selected.role_code },
                { label: '固定版本', children: selected.version },
                {
                  label: '角色标识',
                  children: <Typography.Text copyable>{selected.id}</Typography.Text>,
                },
                { label: '权限数量', children: selected.capabilities.length },
              ]}
            />
            <section className="g-detail-section">
              <h3>完整角色权限</h3>
              {catalog.error ? (
                <Failure error={catalog.error} retry={() => void catalog.refetch()} />
              ) : (
                <div className="g-role-capability-detail">
                  {selected.capabilities.map((code) => {
                    const cap = catalog.data?.capabilities.find((cap) => cap.code === code);
                    return (
                      <div className="g-role-capability-row" key={code}>
                        <Link to={link('catalog', 'capability', code)}>{code}</Link>
                        <span>{cap?.resource_type ?? '当前未登记'}</span>
                        {cap && (
                          <Tag
                            color={
                              cap.disabled
                                ? 'error'
                                : cap.risk_level === 'HIGH'
                                  ? 'warning'
                                  : undefined
                            }
                          >
                            {cap.disabled
                              ? '已停用'
                              : cap.risk_level === 'HIGH'
                                ? '高风险'
                                : '普通风险'}
                          </Tag>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </section>
            <section className="g-detail-section">
              <h3>关联菜单</h3>
              {catalog.error ? (
                <Failure error={catalog.error} />
              ) : catalog.isPending ? (
                <Skeleton active />
              ) : (
                <div className="g-directory-links">
                  {catalog.data?.menus
                    .filter((menu) =>
                      selected.capabilities.some((code) => menu.any_of.includes(code)),
                    )
                    .map((menu) => (
                      <Link key={menu.code} to={link('menus', 'menu', menu.code)}>
                        {menuTitle(menu)}
                      </Link>
                    ))}
                  {!catalog.data?.menus.some((menu) =>
                    selected.capabilities.some((code) => menu.any_of.includes(code)),
                  ) && <span>当前没有直接关联菜单</span>}
                </div>
              )}
            </section>
            <section className="g-detail-section">
              <h3>版本差异与授权引用</h3>
              {impact.error ? (
                <Failure error={impact.error} retry={() => void impact.refetch()} />
              ) : impact.isPending ? (
                <Skeleton active />
              ) : (
                <Descriptions
                  column={1}
                  items={[
                    { label: '引用此版本的授权数', children: impact.data?.referencing_grant_count },
                    {
                      label: '新增能力',
                      children: <CapabilityList values={impact.data?.added ?? []} />,
                    },
                    {
                      label: '移除能力',
                      children: <CapabilityList values={impact.data?.removed ?? []} />,
                    },
                    { label: '版本处理', children: '旧授权保持固定版本；扩权需要重新授予或申请。' },
                  ]}
                />
              )}
            </section>
            <div className="g-directory-links">
              <Link to={link('grants', 'grant_role', selected.id)}>用此角色授予成员</Link>
              <Link to={link('policies')}>查看申请策略</Link>
              <Link to={link('audit')}>查看授权审计</Link>
            </div>
          </>
        )}
      </GovernanceModal>
      {catalogOpen && (
        <CatalogEditor
          partition={partition}
          application={partition.application_id}
          close={() => setCatalogOpen(false)}
          saved={refresh}
        />
      )}
      {roleOpen && catalog.data && (
        <RoleEditor
          catalog={catalog.data}
          copy={copy}
          close={() => setRoleOpen(false)}
          saved={refresh}
        />
      )}
      {grantOpen && catalog.data && (
        <GrantEditor
          catalog={catalog.data}
          roles={roleDirectory.data ?? []}
          initialRole={params.get('grant_role') ?? undefined}
          close={() => setGrantOpen(false)}
          saved={refresh}
        />
      )}
    </>
  );
}

function RoleEditor({
  catalog,
  copy,
  close,
  saved,
}: {
  catalog: PublishedCatalog;
  copy?: Role;
  close: () => void;
  saved: () => void;
}) {
  const { partition } = useGovernanceContext();
  const [form] = Form.useForm();
  const command = useCommand(createRole);
  const [dirty, setDirty] = useState(false);
  const selection = useCatalogSelection(catalog, partition, command.busy || command.unknown);
  const resource = Form.useWatch('resource_type', form);
  const initialTypes = new Set(
    copy?.capabilities
      .map((code) => catalog.capabilities.find((cap) => cap.code === code)?.resource_type)
      .filter(Boolean),
  );
  const finish = async (values: {
    role_code: string;
    role_version: number;
    capabilities: string[];
  }) => {
    if (!command.unknown && !(await selection.verify())) return;
    const response = await command.send((id) => ({
      ...partition,
      command_id: id,
      role_code: values.role_code,
      role_version: values.role_version,
      capabilities: values.capabilities,
    }));
    if (response) saved();
  };
  const cancel = () => {
    if (command.busy || command.unknown) return;
    if (dirty && !command.result)
      Modal.confirm({
        title: '放弃尚未提交的角色编辑？',
        okText: '放弃编辑',
        cancelText: '继续编辑',
        onOk: close,
      });
    else close();
  };
  return (
    <GovernanceModal
      title={copy ? '创建角色新版本' : '创建角色版本'}
      open
      onCancel={cancel}
      width={640}
      maskClosable={false}
      footer={
        !command.result && (
          <Button
            type="primary"
            loading={command.busy || selection.checking}
            disabled={!command.unknown && selection.changed}
            onClick={() => (command.unknown ? void finish(form.getFieldsValue()) : form.submit())}
          >
            {command.unknown ? '重试原命令' : '创建固定版本'}
          </Button>
        )
      }
      closeDisabled={command.busy || command.unknown}
    >
      {selection.notice}
      {!!command.error && <Failure error={command.error} />}
      {command.unknown && (
        <Alert type="warning" message="提交结果尚未确认。字段已冻结，请原样重试。" />
      )}
      {command.result ? (
        <Alert
          type="success"
          showIcon
          message={`已创建 ${command.result.role_code} · 版本 ${command.result.version}`}
          description="创建角色不自动授予权限，也不修改既有授权。"
        />
      ) : (
        <>
          <Form
            form={form}
            layout="vertical"
            onValuesChange={() => setDirty(true)}
            onFinish={finish}
            disabled={command.busy || command.unknown || selection.checking}
            initialValues={{
              resource_type: initialTypes.size === 1 ? [...initialTypes][0] : undefined,
              role_code: copy?.role_code,
              role_version: copy ? copy.version + 1 : 1,
              capabilities: copy?.capabilities ?? [],
            }}
          >
            <Form.Item
              name="role_code"
              label="角色编码"
              rules={[
                { required: true, message: '请输入稳定角色编码' },
                { pattern: /^[a-z][a-z0-9._-]{0,99}$/, message: '使用小写字母开头的稳定编码' },
              ]}
            >
              <Input disabled={!!copy} />
            </Form.Item>
            <Form.Item name="role_version" label="新版本号" rules={[{ required: true }]}>
              <InputNumber min={1} precision={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              name="resource_type"
              label="角色资源类型"
              rules={[{ required: true, message: '先选择真实资源类型' }]}
            >
              <Select
                aria-label="角色资源类型"
                showSearch
                options={selection.catalog.resource_types.map((type) => ({
                  value: type.code,
                  label: type.code,
                }))}
              />
            </Form.Item>
            <Form.Item
              name="capabilities"
              label="明确选择能力"
              rules={[
                {
                  validator: (_, values: string[] = []) => {
                    const error = capabilitySelectionError(selection.catalog, resource, values);
                    return error ? Promise.reject(new Error(error)) : Promise.resolve();
                  },
                },
              ]}
            >
              <PublishedCatalogSelector
                catalog={selection.catalog}
                resource={resource}
                disabled={command.busy || command.unknown || selection.checking}
              />
            </Form.Item>
          </Form>
        </>
      )}
    </GovernanceModal>
  );
}

interface GrantInput extends ScopeInput {
  member_id: string;
  role_id: string;
  duration_minutes: number;
  source_id: string;
}
function GrantEditor({
  catalog,
  roles,
  initialRole,
  close,
  saved,
}: {
  catalog: PublishedCatalog;
  roles: Role[];
  initialRole?: string;
  close: () => void;
  saved: () => void;
}) {
  const { partition, queryKey } = useGovernanceContext();
  const [form] = Form.useForm<GrantInput>();
  const [after, setAfter] = useState<string>();
  const [dirty, setDirty] = useState(false);
  const candidates = useQuery({
    queryKey: [...queryKey, 'members', after],
    queryFn: () => members(partition, after),
    retry: false,
    gcTime: 0,
  });
  const command = useCommand(grantScoped);
  const roleId = Form.useWatch('role_id', form);
  const role = roles.find((r) => r.id === roleId);
  const selection = useCatalogSelection(catalog, partition, command.busy || command.unknown);
  const eligibility = roleScopeEligibility(role, selection.catalog);
  const cancel = () => {
    if (command.busy || command.unknown) return;
    if (dirty && !command.result)
      Modal.confirm({
        title: '放弃尚未提交的成员授予？',
        okText: '放弃编辑',
        cancelText: '继续编辑',
        onOk: close,
      });
    else close();
  };
  const finish = async (values: GrantInput) => {
    if (!command.unknown && !(await selection.verify())) return;
    const response = await command.send((id) => {
      const target = candidates.data!.items.find((m) => m.membership_id === values.member_id)!;
      const from = new Date();
      return {
        ...partition,
        command_id: id,
        member_id: target.membership_id,
        member_generation: target.generation,
        role_id: values.role_id,
        scope_rule: scopeRule(values),
        source_id: values.source_id,
        valid_from: from.toISOString(),
        valid_to: new Date(from.getTime() + values.duration_minutes * 60000).toISOString(),
      };
    });
    if (response) saved();
  };
  return (
    <GovernanceModal
      title="授予成员权限"
      footer={
        !command.result && (
          <Button
            type="primary"
            loading={command.busy || selection.checking}
            disabled={
              !command.unknown &&
              (selection.changed ||
                !!eligibility.reason ||
                candidates.isPending ||
                !!candidates.error)
            }
            onClick={() => (command.unknown ? void finish(form.getFieldsValue()) : form.submit())}
          >
            {command.unknown ? '重试原命令' : '提交授予'}
          </Button>
        )
      }
      open
      onCancel={cancel}
      width={720}
      maskClosable={false}
      closeDisabled={command.busy || command.unknown}
    >
      {selection.notice}
      {candidates.error && !command.unknown && !command.busy ? (
        <Failure error={candidates.error} retry={() => void candidates.refetch()} />
      ) : (
        <>
          {!!command.error && <Failure error={command.error} />}
          {command.unknown && (
            <Alert type="warning" message="结果尚未确认。重试会使用相同成员、范围、时间和命令。" />
          )}
          {command.result ? (
            <Alert
              type="info"
              showIcon
              message="授权已受理，等待实际投影生效"
              description={`授权标识：${command.result.id}。请关闭并刷新执行状态。`}
            />
          ) : (
            <>
              <Form
                form={form}
                layout="vertical"
                initialValues={{
                  role_id: roles.some((role) => role.id === initialRole) ? initialRole : undefined,
                  duration_minutes: Math.min(
                    30,
                    Math.floor(selection.catalog.max_duration_seconds / 60),
                  ),
                }}
                onValuesChange={() => setDirty(true)}
                onFinish={finish}
                disabled={
                  command.busy || command.unknown || candidates.isPending || selection.checking
                }
              >
                <Form.Item
                  name="member_id"
                  label="受益成员"
                  rules={[{ required: true, message: '请选择当前有效成员' }]}
                >
                  <Select
                    aria-label="受益成员"
                    showSearch
                    optionFilterProp="label"
                    options={candidates.data?.items
                      .filter((m) => m.membership_id !== selection.catalog.membership_id)
                      .map((m) => ({
                        value: m.membership_id,
                        label: `${m.member_kind} · ${m.membership_id} · 第${m.generation}代`,
                      }))}
                  />
                </Form.Item>
                {candidates.data?.next_cursor && (
                  <Button
                    onClick={() => {
                      form.setFieldValue('member_id', undefined);
                      setAfter(candidates.data!.next_cursor!);
                    }}
                  >
                    下一页成员
                  </Button>
                )}
                <Form.Item
                  name="role_id"
                  label="固定角色版本"
                  rules={[{ required: true, message: '请选择角色版本' }]}
                >
                  <Select
                    aria-label="固定角色版本"
                    options={roles.map((r) => ({
                      value: r.id,
                      label: `${r.role_code} · v${r.version}${roleScopeEligibility(r, selection.catalog).reason ? `（${roleScopeEligibility(r, selection.catalog).reason}）` : ''}`,
                      disabled: !!roleScopeEligibility(r, selection.catalog).reason,
                    }))}
                    onChange={() =>
                      form.setFieldsValue({
                        resource_type: undefined,
                        scope_kind: undefined,
                        scope_values: undefined,
                      })
                    }
                  />
                </Form.Item>
                {role && eligibility.reason && (
                  <Alert type="warning" message={eligibility.reason} style={{ marginBottom: 12 }} />
                )}
                <ScopeFields resourceTypes={eligibility.resourceTypes} />
                <Form.Item
                  name="duration_minutes"
                  label="有效分钟数（从提交时开始）"
                  rules={[{ required: true }]}
                >
                  <InputNumber
                    min={1}
                    max={Math.floor(selection.catalog.max_duration_seconds / 60)}
                    precision={0}
                    style={{ width: '100%' }}
                  />
                </Form.Item>
                <Form.Item
                  name="source_id"
                  label="授权来源说明"
                  rules={[{ required: true, message: '填写便于追溯的来源说明' }, { max: 100 }]}
                >
                  <Input placeholder="例如：门店协作接入批次" />
                </Form.Item>
              </Form>
            </>
          )}
        </>
      )}
    </GovernanceModal>
  );
}
