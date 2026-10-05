import { createContext, useContext, useEffect, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { Alert, Button, ConfigProvider, Dropdown, Empty, Select, Skeleton } from 'antd';
import {
  AppstoreOutlined,
  ArrowRightOutlined,
  AuditOutlined,
  CheckOutlined,
  DownOutlined,
  ExportOutlined,
  FileDoneOutlined,
  KeyOutlined,
  LogoutOutlined,
  MenuOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useAuth } from 'react-oidc-context';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Outlet, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import {
  applications,
  organizations,
  type Organization,
  type Partition,
  type PortalApplication,
} from '../../../api/governance';
import {
  applicationSearch,
  contextKey,
  normalizedSearch,
  organizationSearch,
  safeEntry,
  contextualEntry,
} from '../shared/context';
import { GovernanceModal } from '../shared/presentation';
import { EntryState } from '../shared/codes';
import { Failure } from '../shared/feedback';
import { governanceTheme } from '../../../theme/theme';
import { colors } from '../../../theme/colors';
import '../../../styles/governance.css';

export interface GovernanceContext {
  organization: Organization;
  application: PortalApplication;
  partition: Partition;
  queryKey: readonly unknown[];
}
const Context = createContext<GovernanceContext | undefined>(undefined);
export function useGovernanceContext() {
  const context = useContext(Context);
  if (!context) throw new Error('当前应用上下文尚未加载');
  return context;
}
const kindLabels: Record<string, string> = {
  EMPLOYEE: '内部成员',
  PARTNER: '合作成员',
  GUEST: '访客',
};
const routes: Record<string, { title: string; description: string; icon: ReactNode }> = {
  permissions: {
    title: '我的权限',
    description: '查看每一份权限的角色、范围与来源。',
    icon: <KeyOutlined />,
  },
  requests: {
    title: '申请与通知',
    description: '从提交到生效，跟进每一次权限申请。',
    icon: <FileDoneOutlined />,
  },
  menus: {
    title: '菜单权限',
    description: '按菜单查看页面路由与关联权限。',
    icon: <MenuOutlined />,
  },
  catalog: {
    title: '权限目录',
    description: '浏览当前应用已发布的权限、资源和角色关联。',
    icon: <KeyOutlined />,
  },
  roles: {
    title: '角色管理',
    description: '查看固定角色版本及其完整权限，按需创建新版本。',
    icon: <SafetyCertificateOutlined />,
  },
  grants: {
    title: '成员授权',
    description: '核对成员角色、数据范围、有效期与授权来源。',
    icon: <TeamOutlined />,
  },
  access: {
    title: '授权管理',
    description: '用固定角色版本，为合适的成员授予明确范围的权限。',
    icon: <SafetyCertificateOutlined />,
  },
  policies: {
    title: '申请策略',
    description: '定义可申请的角色、范围、期限和审批成员。',
    icon: <SettingOutlined />,
  },
  invitations: {
    title: '外部邀请',
    description: '邀请合作成员加入组织，再按需授予业务权限。',
    icon: <TeamOutlined />,
  },
  audit: {
    title: '授权审计',
    description: '追溯当前应用中的授权操作与处理结果。',
    icon: <AuditOutlined />,
  },
  diagnostic: {
    title: '授权来源详情',
    description: '在同一条来源内确认能力、范围与生效状态。',
    icon: <SafetyCertificateOutlined />,
  },
  reviews: {
    title: '权限复核',
    description: '人工核对固定来源，记录决定并跟进真实撤权结果。',
    icon: <AuditOutlined />,
  },
  personnel: {
    title: '人员变更核对',
    description: '核对人员状态、目录变更及当前与历史授权来源。',
    icon: <TeamOutlined />,
  },
};
const palette = {
  '--g-ink': colors.text,
  '--g-muted': colors.governanceMuted,
  '--g-blue': colors.primary,
  '--g-soft': colors.primarySoft,
  '--g-line': colors.border,
  '--g-canvas': colors.bgLayout,
} as CSSProperties;

/** 上下文与查询绑定真实成员代际；导航仅选择目录，不赋予任何权限。 */
export default function GovernancePage() {
  const auth = useAuth();
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();
  const qc = useQueryClient();
  const [menuOpen, setMenuOpen] = useState(false);
  const subject = `${auth.user?.profile.iss ?? ''}:${auth.user?.profile.sub ?? ''}`;
  const tenant = params.get('tenant') ?? '';
  const orgs = useQuery({
    queryKey: ['governance', subject, 'organizations'],
    queryFn: ({ signal }) => organizations(signal),
    staleTime: 0,
    gcTime: 0,
    retry: false,
  });
  const org = !orgs.error && orgs.data?.find((item) => item.tenant_id === tenant);
  const directoryKey = contextKey(subject, org ? org.membership_id : '', org ? org.generation : 0, {
    tenant_id: tenant,
    application_id: '',
    environment: '',
  });
  const after = params.get('after') ?? undefined;
  const apps = useQuery({
    queryKey: [...directoryKey, 'applications', after],
    queryFn: ({ signal }) => applications(tenant, after, signal),
    enabled: !!org,
    staleTime: 0,
    gcTime: 0,
    retry: false,
  });
  // 目录失败立即关闭旧业务入口，不用缓存数据伪装当前可访问状态。
  const entries = !apps.error ? (apps.data?.items ?? []) : [];
  const app = entries.find(
    (item) =>
      item.application_id === params.get('application') &&
      item.environment === params.get('environment'),
  );
  const partition = {
    tenant_id: tenant,
    application_id: app?.application_id ?? '',
    environment: app?.environment ?? '',
  };
  const scopedKey = contextKey(
    subject,
    org ? org.membership_id : '',
    org ? org.generation : 0,
    partition,
  );
  const scope =
    org && app
      ? { organization: org, application: app, partition, queryKey: scopedKey }
      : undefined;
  const route = location.pathname.split('/')[2] ?? '';
  const home = !route;
  const page = routes[route];
  const homeUrl = `/governance?${app ? applicationSearch(params, app.application_id, app.environment) : organizationSearch(tenant)}`;
  const pathFor = (item: PortalApplication, path: string) =>
    `/governance/${path}?${applicationSearch(params, item.application_id, item.environment)}`;
  useEffect(() => {
    const next = normalizedSearch(params);
    if (!tenant && orgs.data?.length) {
      setParams(organizationSearch(orgs.data[0].tenant_id), { replace: true });
      return;
    }
    // 单一真实应用可自动选中；多应用必须显式选择，不猜测目标分区。
    if (home && !next.has('application') && entries.length === 1) {
      next.set('application', entries[0].application_id);
      next.set('environment', entries[0].environment);
    }
    if (next.toString() !== params.toString()) setParams(next, { replace: true });
  }, [tenant, orgs.data, params, setParams, entries, home]);
  // 同页链接也会创建导航记录；按导航键关闭导航弹层，避免只监听路径时遮住当前任务。
  useEffect(() => {
    setMenuOpen(false);
    document.title = `${page?.title ?? '我的工作台'} · 权限控制台`;
  }, [location.key, page]);
  const changeOrganization = (id: string) => {
    void qc.cancelQueries({ queryKey: ['governance', subject] });
    qc.removeQueries({
      queryKey: ['governance', subject],
      predicate: (query) => query.queryKey[2] !== 'organizations',
    });
    navigate(`/governance?${organizationSearch(id)}`);
  };
  const navigation = (keys: string[]) =>
    keys.map((key) =>
      app ? (
        <Link
          key={key}
          to={pathFor(app, key)}
          aria-current={
            route === key || (key === 'access' && route === 'diagnostic') ? 'page' : undefined
          }
          className="g-nav-item"
        >
          {routes[key].icon}
          <span>{routes[key].title}</span>
        </Link>
      ) : (
        <span key={key} className="g-nav-item g-nav-disabled" aria-disabled="true">
          {routes[key].icon}
          <span>{routes[key].title}</span>
        </span>
      ),
    );
  const sidebar = (
    <>
      <Link className="g-brand" to={homeUrl} aria-label="权限控制台工作台">
        <span className="g-brand-mark" aria-hidden="true">
          <i />
          <i />
          <i />
          <i />
        </span>
        <span>
          权限控制台<small>ACCESS CONSOLE</small>
        </span>
      </Link>
      <nav className="g-navigation" aria-label="权限控制台导航">
        <span className="g-nav-caption">工作空间</span>
        <Link to={homeUrl} className="g-nav-item" aria-current={home ? 'page' : undefined}>
          <AppstoreOutlined />
          <span>我的工作台</span>
        </Link>
        <span className="g-nav-caption">个人中心</span>
        {navigation(['permissions', 'requests'])}
        {app?.management && (
          <>
            <span className="g-nav-caption">应用管理</span>
            {navigation([
              'menus',
              'catalog',
              'roles',
              'grants',
              'personnel',
              'reviews',
              'policies',
              'invitations',
              'audit',
            ])}
          </>
        )}
        {app?.catalog_owner && !app.management && (
          <>
            <span className="g-nav-caption">应用 Owner</span>
            {navigation(['catalog'])}
          </>
        )}
        {!app && <p className="g-nav-hint">选择应用后查看相关权限与任务。</p>}
      </nav>
      <div className="g-sidebar-bottom">
        <span className="g-member-icon">
          <UserOutlined />
        </span>
        <div>
          <strong>{org ? (kindLabels[org.member_kind] ?? org.member_kind) : '组织成员'}</strong>
          <small>{org ? org.tenant_code : '请先选择组织'}</small>
        </div>
      </div>
    </>
  );
  return (
    <ConfigProvider theme={governanceTheme}>
      <div className="governance-shell" style={palette}>
        <a className="g-skip" href="#governance-content">
          跳转到主要内容
        </a>
        <aside className="g-sidebar">{sidebar}</aside>
        <GovernanceModal
          title="工作空间导航"
          open={menuOpen}
          onCancel={() => setMenuOpen(false)}
          width={480}
          rootClassName="g-navigation-modal"
        >
          <div className="governance-shell g-modal-navigation" style={palette}>
            {sidebar}
          </div>
        </GovernanceModal>
        <div className="g-workspace">
          <header className="g-topbar">
            <Button
              className="g-mobile-menu"
              type="text"
              icon={<MenuOutlined />}
              aria-label="打开工作空间导航"
              onClick={() => setMenuOpen(true)}
            />
            <div className="g-organization">
              <span className="g-context-label">组织</span>
              <Select
                aria-label="当前组织"
                variant="borderless"
                placeholder="选择组织"
                value={org ? tenant : undefined}
                loading={orgs.isFetching}
                onChange={changeOrganization}
                options={orgs.data?.map((item) => ({
                  value: item.tenant_id,
                  label: item.tenant_code,
                }))}
              />
            </div>
            {app && (
              <div className="g-current-app">
                <span aria-hidden="true">/</span>
                <span>{app.application_id}</span>
                <span className="g-environment">{app.environment}</span>
              </div>
            )}
            <Dropdown
              trigger={['click']}
              menu={{
                items: [
                  {
                    key: 'invite',
                    icon: <TeamOutlined />,
                    label: <Link to="/invitations/accept">接受邀请</Link>,
                  },
                  { type: 'divider' },
                  {
                    key: 'logout',
                    icon: <LogoutOutlined />,
                    label: '退出登录',
                    onClick: () => void auth.signoutRedirect(),
                  },
                ],
              }}
            >
              <Button className="g-account" type="text" aria-label="账户菜单">
                <span className="g-avatar">
                  <UserOutlined />
                </span>
                <span className="g-account-label">我的账户</span>
                <DownOutlined />
              </Button>
            </Dropdown>
          </header>
          {app && (
            <div className="g-mobile-scope">
              <span>{app.application_id}</span>
              <span className="g-environment">{app.environment}</span>
            </div>
          )}
          <main id="governance-content" className="g-content" tabIndex={-1}>
            <div className="g-page-heading">
              <div>
                <div className="g-eyebrow">
                  {home ? 'WORKSPACE' : (app?.application_id ?? 'ACCESS CONSOLE')}
                  <span aria-hidden="true" />
                  {home ? '工作空间' : '权限控制台'}
                </div>
                <h1>{home ? '我的工作台' : (page?.title ?? '权限控制台')}</h1>
                <p>{home ? '让每一次访问，都有清晰的依据。' : page?.description}</p>
              </div>
              {home && org && (
                <Button
                  icon={<ReloadOutlined />}
                  loading={apps.isFetching}
                  onClick={() => void apps.refetch()}
                >
                  刷新应用
                </Button>
              )}
            </div>
            {orgs.error ? (
              <Failure error={orgs.error} retry={() => void orgs.refetch()} />
            ) : orgs.isPending ? (
              <div className="g-loading" role="status" aria-label="正在加载组织">
                <Skeleton active />
              </div>
            ) : !org ? (
              <div className="g-empty-panel">
                <Empty
                  description={tenant ? '当前组织不可用，请重新选择有效组织' : '当前没有有效组织'}
                />
              </div>
            ) : apps.error ? (
              <Failure error={apps.error} retry={() => void apps.refetch()} />
            ) : apps.isPending ? (
              <div className="g-loading" role="status" aria-label="正在加载应用">
                <Skeleton active />
              </div>
            ) : home ? (
              <>
                <section aria-labelledby="g-applications">
                  <div className="g-section-heading">
                    <h2 id="g-applications">关联应用</h2>
                    <span>业务访问与应用管理分别授权</span>
                  </div>
                  {!entries.length ? (
                    <div className="g-empty-panel">
                      <Empty description="当前组织暂无关联应用" />
                      <p>关联应用后，可在这里查看入口与权限。</p>
                    </div>
                  ) : (
                    <div className="g-application-grid">
                      {entries.map((item) => {
                        const menus = item.menus.filter((menu) => safeEntry(menu.href));
                        return (
                          <article
                            className="g-application"
                            key={`${item.application_id}/${item.environment}`}
                          >
                            <div className="g-app-intro">
                              <div className="g-app-icon">
                                <AppstoreOutlined />
                              </div>
                              <div>
                                <span className="g-label">APPLICATION</span>
                                <h3>{item.application_id}</h3>
                              </div>
                              <span className="g-environment">{item.environment}</span>
                            </div>
                            <div className="g-access-area">
                              <div className="g-access-caption">
                                <span>业务访问</span>
                                <span
                                  className={`g-status ${menus.length ? 'g-status-available' : ''}`}
                                >
                                  {menus.length
                                    ? '有可用入口'
                                    : item.entry_state === EntryState.UNAVAILABLE
                                      ? '暂时无法确认'
                                      : '暂无可用入口'}
                                </span>
                              </div>
                              {menus.length ? (
                                <div className="g-business-links">
                                  {menus.map((menu) => (
                                    <a
                                      key={menu.code}
                                      href={contextualEntry(menu.href, tenant, item.environment)}
                                      target="_blank"
                                      rel="noopener noreferrer"
                                    >
                                      进入 {menu.label ?? menu.code}
                                      <ExportOutlined />
                                    </a>
                                  ))}
                                </div>
                              ) : (
                                <p>
                                  {item.entry_state === EntryState.UNAVAILABLE
                                    ? '暂时无法确认业务权限。请稍后刷新，或查看申请进度。'
                                    : '当前没有可进入的业务页面。你仍可查看权限来源，或提交权限申请。'}
                                </p>
                              )}
                            </div>
                            <div className="g-app-actions">
                              <span className="g-management-state">
                                {item.management ? (
                                  <>
                                    <CheckOutlined />
                                    具有应用管理权限
                                  </>
                                ) : (
                                  <>未获得应用管理权限</>
                                )}
                              </span>
                              {item.catalog_owner && !item.management ? (
                                <Link className="g-primary-link" to={pathFor(item, 'catalog')}>
                                  治理应用目录
                                  <ArrowRightOutlined />
                                </Link>
                              ) : item.management ? (
                                <Link className="g-primary-link" to={pathFor(item, 'access')}>
                                  管理授权
                                  <ArrowRightOutlined />
                                </Link>
                              ) : (
                                <Link className="g-primary-link" to={pathFor(item, 'permissions')}>
                                  查看我的权限
                                  <ArrowRightOutlined />
                                </Link>
                              )}
                            </div>
                            {entries.length > 1 && (
                              <div className="g-multi-actions">
                                <Link to={pathFor(item, 'permissions')}>我的权限</Link>
                                <Link to={pathFor(item, 'requests')}>申请与通知</Link>
                              </div>
                            )}
                          </article>
                        );
                      })}
                    </div>
                  )}
                  {(after || apps.data?.next_cursor) && (
                    <div className="g-pagination">
                      {after && (
                        <Button onClick={() => setParams(organizationSearch(tenant))}>
                          返回应用首页
                        </Button>
                      )}
                      {apps.data?.next_cursor && (
                        <Button
                          onClick={() => {
                            const next = organizationSearch(tenant);
                            next.set('after', apps.data!.next_cursor!);
                            setParams(next);
                          }}
                        >
                          下一页应用
                        </Button>
                      )}
                    </div>
                  )}
                </section>
                {app && (
                  <section className="g-personal-section" aria-labelledby="g-personal">
                    <div className="g-section-heading">
                      <h2 id="g-personal">我的权限事务</h2>
                      <span>
                        {app.application_id} / {app.environment}
                      </span>
                    </div>
                    <div className="g-task-list">
                      <Link to={pathFor(app, 'permissions')} className="g-task">
                        <span className="g-task-icon">
                          <KeyOutlined />
                        </span>
                        <div>
                          <h3>我的权限</h3>
                          <p>了解我能做什么，以及权限从哪里来</p>
                        </div>
                        <ArrowRightOutlined />
                      </Link>
                      <Link to={pathFor(app, 'requests')} className="g-task">
                        <span className="g-task-icon">
                          <FileDoneOutlined />
                        </span>
                        <div>
                          <h3>申请与通知</h3>
                          <p>申请所需权限，跟进审批与生效进度</p>
                        </div>
                        <ArrowRightOutlined />
                      </Link>
                    </div>
                  </section>
                )}
                <div className="g-principle">
                  <SafetyCertificateOutlined />
                  <p>
                    <strong>权限始终有边界</strong>
                    <span>
                      角色、资源范围与有效期共同定义一次访问。每份授权独立生效，也可独立追溯。
                    </span>
                  </p>
                </div>
              </>
            ) : scope ? (
              <Context.Provider key={JSON.stringify(scopedKey)} value={scope}>
                <div className="g-route-content">
                  <Outlet />
                </div>
              </Context.Provider>
            ) : (
              <Alert
                type="warning"
                showIcon
                message="当前应用不可用"
                description={
                  <span>
                    此应用不在当前组织的关联目录中。<Link to={homeUrl}>返回工作台重新选择</Link>
                  </span>
                }
              />
            )}
            <footer className="g-footer">
              <span>权限控制台</span>
              <span>清晰授权 · 可追溯访问</span>
            </footer>
          </main>
        </div>
      </div>
    </ConfigProvider>
  );
}
