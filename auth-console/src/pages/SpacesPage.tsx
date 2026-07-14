import { useEffect, useMemo, useState } from 'react'
import { Alert, App, Button, Card, Col, Input, Popconfirm, Row, Space, Switch, Tag, Tree, Typography } from 'antd'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import { PageHeader } from '../components/layout/PageHeader'
import { ObjectTypeSelect, PermissionSelect } from '../components/domain/selects'
import { RefBadge, TupleText } from '../components/domain/RefBadge'
import { AllowDenyResult } from '../components/domain/AllowDenyResult'
import { EmptyState, ErrorState } from '../components/common/AsyncState'
import { SpaceMemberCard } from '../components/domain/SpaceMemberCard'
import { OBJECT_TYPES, relationsFor, type ObjectType } from '../domain/lexicon'
import { listRelationships, type Relationship } from '../api/authz'
import { humanizeError, useCheck, useExpand, useGrant, useLookupResources, useRevoke } from '../hooks/useAuthz'
import { expandToTree } from '../domain/expandTree'
import { isAdmin, useAuthStore } from '../store/authStore'

// 结构关系(所属组织/空间/文件夹)不是"成员角色",排除出成员管理。
const STRUCTURAL = new Set(['parent_org', 'parent_space', 'parent_folder'])
// 本页只管资源对象(space/folder/document)。
const RESOURCE_TYPES = ['space', 'folder', 'document'] as const
type ResourceType = (typeof RESOURCE_TYPES)[number]
const isResourceType = (v: string | null): v is ResourceType => !!v && (RESOURCE_TYPES as readonly string[]).includes(v)
// 顶部资源类型选择器排除的类型(从词典派生,避免与 RESOURCE_TYPES 手工维护互补集漂移)。
const NON_RESOURCE: ObjectType[] = OBJECT_TYPES.map((o) => o.value).filter((v) => !(RESOURCE_TYPES as readonly string[]).includes(v))
// 「我可管理/编辑的」快捷列表反查用的权限(folder/document 无 manage)。
const LANDING_PERM: Record<ResourceType, string> = { space: 'manage', folder: 'edit', document: 'edit' }
const MINE_LABEL: Record<ResourceType, string> = { space: '我可管理的空间', folder: '我可编辑的文件夹', document: '我可编辑的文档' }

// 成员关系 = 可授予关系去掉结构关系与 public_viewer(后者单独做开关)。
const memberRelations = (t: ObjectType) => relationsFor(t).filter((r) => !STRUCTURAL.has(r) && r !== 'public_viewer')
const hasPublicLink = (t: ObjectType) => relationsFor(t).includes('public_viewer')

export default function SpacesPage() {
  const { message } = App.useApp()
  const qc = useQueryClient()
  const [params, setParams] = useSearchParams()

  const typeParam = params.get('type')
  const resourceType: ResourceType = isResourceType(typeParam) ? typeParam : 'space'
  const resourceId = params.get('id') ?? ''
  const [idInput, setIdInput] = useState(resourceId)

  const authorities = useAuthStore((s) => s.authorities)
  const myId = useAuthStore((s) => s.userId)
  const canWrite = isAdmin(authorities)

  const relKey = ['relationships', resourceType, resourceId] as const
  const rels = useQuery({
    queryKey: relKey,
    queryFn: () => listRelationships(resourceType, resourceId),
    enabled: !!resourceId,
  })
  const afterWrite = () => qc.invalidateQueries({ queryKey: relKey })

  // 所有 mutation 先声明,handlers 再引用(避免 TDZ)。
  const grant = useGrant()
  const revoke = useRevoke()
  const lookup = useLookupResources()
  const check = useCheck()
  const expand = useExpand()

  // 自检输入态。
  const [checkUser, setCheckUser] = useState('')
  const [permission, setPermission] = useState<string>()

  // URL(前进/后退/深链)为选中态单一源:resourceId 变化时同步输入框。
  useEffect(() => {
    setIdInput(resourceId)
  }, [resourceId])

  // 按 relation 分桶。
  const groups = useMemo(() => {
    const m = new Map<string, Relationship[]>()
    for (const r of rels.data ?? []) {
      const arr = m.get(r.relation) ?? []
      arr.push(r)
      m.set(r.relation, arr)
    }
    return m
  }, [rels.data])

  // 公开态由列表派生(存在 public_viewer@user:* 即公开)。
  const isPublic = useMemo(
    () => (rels.data ?? []).some((r) => r.relation === 'public_viewer' && r.subject.type === 'user' && r.subject.id === '*'),
    [rels.data],
  )

  // 切对象/切类型时清掉与旧对象绑定的瞬态结果(快捷列表、自检)。
  const resetTransient = () => {
    lookup.reset()
    check.reset()
    expand.reset()
  }

  const onType = (v: ObjectType) => {
    setParams({ type: v })
    setPermission(undefined)
    resetTransient()
  }

  const load = (id = idInput) => {
    const trimmed = id.trim()
    if (!trimmed) return message.warning('填入对象 id')
    resetTransient()
    setParams({ type: resourceType, id: trimmed })
  }

  // ── 公开链接开关 ── grant/revoke 在本页仅服务公开开关。
  // busy 并入 rels.isFetching:写后到关系读回完成前不让再操作(避免陈旧态)。
  const publicBusy = grant.isPending || revoke.isPending || rels.isFetching
  const publicBody = { resourceType, resourceId, relation: 'public_viewer', subjectType: 'user', subjectId: '*' }
  const openPublic = () =>
    grant.mutate(publicBody, {
      onSuccess: () => {
        message.success('已设为公开')
        afterWrite()
      },
      onError: (e) => message.error(humanizeError(e)),
    })
  const closePublic = () =>
    revoke.mutate(publicBody, {
      onSuccess: () => {
        message.success('已收回公开')
        afterWrite()
      },
      onError: (e) => message.error(humanizeError(e)),
    })

  // ── 快捷列表:我可管理/编辑的对象 ──
  const listMine = () => {
    if (!myId) return message.warning('未获取到当前用户')
    lookup.mutate(
      { subjectType: 'user', subjectId: myId, permission: LANDING_PERM[resourceType], resourceType },
      { onError: (e) => message.error(humanizeError(e)) },
    )
  }

  // ── 自检:check + expand ──
  const runCheck = () => {
    if (!resourceId) return message.warning('先加载一个对象')
    if (!checkUser.trim() || !permission) return message.warning('填 user id 并选权限')
    check.mutate(
      { subjectType: 'user', subjectId: checkUser.trim(), permission, resourceType, resourceId },
      { onError: (e) => message.error(humanizeError(e)) },
    )
    expand.mutate(
      { resourceType, resourceId, permission },
      { onError: (e) => message.error(humanizeError(e)) },
    )
  }

  const noMembers = !!resourceId && !rels.isFetching && (rels.data?.length ?? 0) === 0

  return (
    <>
      <PageHeader title="空间 / 知识库" description="以对象为中心的成员治理:按角色分组、增删成员、公开链接、判权自检" />

      {!canWrite && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="只读身份:可查看成员与自检;增删成员 / 改公开链接需 authz-admin。"
        />
      )}

      {/* 顶部:选类型 + 输入 id + 快捷列表 + 公开开关 */}
      <Card style={{ marginBottom: 16 }}>
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Space.Compact style={{ width: '100%' }}>
            <div style={{ width: 160 }}>
              <ObjectTypeSelect value={resourceType} onChange={onType} exclude={NON_RESOURCE} />
            </div>
            <Input
              placeholder="对象 id(带租户前缀,如 acme_kb)"
              value={idInput}
              onChange={(e) => setIdInput(e.target.value)}
              onPressEnter={() => load()}
            />
            <Button type="primary" onClick={() => load()}>
              加载成员
            </Button>
            <Button onClick={listMine} loading={lookup.isPending}>
              {MINE_LABEL[resourceType]}
            </Button>
          </Space.Compact>

          {lookup.data && (
            <div>
              <Typography.Text type="secondary">点选回填:</Typography.Text>{' '}
              {lookup.data.resourceIds.length === 0 ? (
                <Typography.Text type="secondary">(无——你在 ReBAC 图里可能非该类对象成员;直接手输 id 即可)</Typography.Text>
              ) : (
                lookup.data.resourceIds.map((id) => (
                  <span key={id} style={{ cursor: 'pointer', marginRight: 4 }} onClick={() => load(id)}>
                    <RefBadge type={resourceType} id={id} />
                  </span>
                ))
              )}
            </div>
          )}

          {resourceId && hasPublicLink(resourceType) && (
            <div>
              <Space wrap>
                <Typography.Text strong>公开链接</Typography.Text>
                {isPublic ? (
                  <Popconfirm
                    title="收回公开?收回后仅授权成员可见。"
                    okText="收回"
                    okButtonProps={{ danger: true }}
                    onConfirm={closePublic}
                    disabled={!canWrite}
                  >
                    <Switch checked disabled={!canWrite} loading={publicBusy} />
                  </Popconfirm>
                ) : (
                  <Switch checked={false} disabled={!canWrite} loading={publicBusy} onChange={openPublic} />
                )}
                {isPublic && <Tag color="gold">公开</Tag>}
              </Space>
              <div style={{ marginTop: 6 }}>
                <Typography.Text type="secondary">开启后任何登录用户可查看。元组:</Typography.Text>{' '}
                <TupleText text={`${resourceType}:${resourceId}#public_viewer@user:*`} />
              </div>
            </div>
          )}
        </Space>
      </Card>

      {/* 主体:成员区 + 自检区 */}
      {!resourceId ? (
        <Card>
          <EmptyState description="选择资源类型并输入对象 id 后加载成员" />
        </Card>
      ) : rels.isError ? (
        <Card>
          <ErrorState message={humanizeError(rels.error)} onRetry={() => rels.refetch()} />
        </Card>
      ) : (
        <Row gutter={16}>
          <Col xs={24} lg={15}>
            <Card
              title={
                <Space>
                  成员 · <RefBadge type={resourceType} id={resourceId} />
                </Space>
              }
              loading={rels.isFetching}
            >
              {noMembers && (
                <Alert
                  type="info"
                  showIcon
                  style={{ marginBottom: 12 }}
                  message="该对象暂无任何直接成员(可能尚未授予,或对象 id 不存在)。可在下方各角色卡添加。"
                />
              )}
              {memberRelations(resourceType).map((rel) => (
                <SpaceMemberCard
                  key={rel}
                  resourceType={resourceType}
                  resourceId={resourceId}
                  relation={rel}
                  members={groups.get(rel) ?? []}
                  canWrite={canWrite}
                  myId={myId}
                />
              ))}
            </Card>
          </Col>

          <Col xs={24} lg={9}>
            <Card title="判权自检" style={{ minHeight: 300 }}>
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                <div>
                  <Typography.Text strong>用户</Typography.Text>
                  <Input
                    placeholder="user id"
                    value={checkUser}
                    onChange={(e) => setCheckUser(e.target.value)}
                    onPressEnter={runCheck}
                    style={{ marginTop: 6 }}
                  />
                  {myId && (
                    <Button size="small" type="link" style={{ paddingLeft: 0 }} onClick={() => setCheckUser(myId)}>
                      用我自己({myId.slice(0, 8)}…)
                    </Button>
                  )}
                </div>
                <div>
                  <Typography.Text strong>权限(可判定)</Typography.Text>
                  <div style={{ marginTop: 6 }}>
                    <PermissionSelect resourceType={resourceType} value={permission} onChange={setPermission} />
                  </div>
                </div>
                <Button type="primary" block onClick={runCheck} loading={check.isPending}>
                  运行自检
                </Button>
                {check.data && (
                  <>
                    <AllowDenyResult allowed={check.data.allowed} />
                    {expand.data != null && (
                      <Card size="small" title="判定路径(展开)">
                        <div className="scroll-x">
                          <Tree treeData={expandToTree(expand.data)} defaultExpandAll selectable={false} />
                        </div>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          展示该权限的全部授权路径;在叶子里找到目标主体即为“因何 allow”。
                        </Typography.Text>
                      </Card>
                    )}
                  </>
                )}
              </Space>
            </Card>
          </Col>
        </Row>
      )}
    </>
  )
}
