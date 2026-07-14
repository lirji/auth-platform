import { useState } from 'react'
import { Alert, App, Button, Card, Col, Input, List, Row, Segmented, Space, Spin, Tree, Typography } from 'antd'
import { ObjectTypeSelect, PermissionSelect } from '../components/domain/selects'
import { RefBadge } from '../components/domain/RefBadge'
import { AllowDenyResult } from '../components/domain/AllowDenyResult'
import { PageHeader } from '../components/layout/PageHeader'
import { EmptyState, ErrorState, PageSkeleton } from '../components/common/AsyncState'
import type { ObjectType } from '../domain/lexicon'
import { expandToTree } from '../domain/expandTree'
import { humanizeError, useCheck, useExpand, useLookupResources, useLookupSubjects } from '../hooks/useAuthz'
import { useAuthStore } from '../store/authStore'

type Mode = 'check' | 'resources' | 'subjects'
const SUBJECT_TYPES: ObjectType[] = ['space', 'folder', 'document', 'dept', 'patient', 'encounter']

export default function PlaygroundPage() {
  const { message } = App.useApp()
  const [mode, setMode] = useState<Mode>('check')
  const [subjectType, setSubjectType] = useState<ObjectType>('user')
  const [subjectId, setSubjectId] = useState('')
  const [resourceType, setResourceType] = useState<ObjectType>('document')
  const [resourceId, setResourceId] = useState('')
  const [permission, setPermission] = useState<string>()

  const myId = useAuthStore((s) => s.userId)
  const check = useCheck()
  const expand = useExpand()
  const lookRes = useLookupResources()
  const lookSub = useLookupSubjects()
  const running = check.isPending || expand.isPending || lookRes.isPending || lookSub.isPending

  const run = () => {
    if (!permission) return message.warning('选一个权限')
    if (mode === 'check') {
      if (!subjectId || !resourceId) return message.warning('主体 id、资源 id 都要填')
      check.mutate({ subjectType, subjectId, permission, resourceType, resourceId })
      expand.mutate({ resourceType, resourceId, permission })
      return
    }
    if (mode === 'resources') {
      if (!subjectId) return message.warning('主体 id 要填')
      return lookRes.mutate({ subjectType, subjectId, permission, resourceType })
    }
    if (!resourceId) return message.warning('资源 id 要填')
    return lookSub.mutate({ resourceType, resourceId, permission, subjectType })
  }

  return (
    <>
      <PageHeader title="权限调试器" description="实时判定(check)+ 反查(某主体能看哪些 / 谁能看某对象)+ 判定路径(expand)" />
      <Row gutter={16}>
        <Col xs={24} lg={10}>
          <Card title="输入">
            <Segmented
              value={mode}
              onChange={(v) => setMode(v as Mode)}
              block
              options={[
                { label: '判定 Check', value: 'check' },
                { label: '反查资源', value: 'resources' },
                { label: '反查主体', value: 'subjects' },
              ]}
              style={{ marginBottom: 16 }}
            />
            <Space direction="vertical" style={{ width: '100%' }} size="middle">
              {(mode === 'check' || mode === 'resources') && (
                <div>
                  <label htmlFor="pg-subj-type" style={{ fontWeight: 600 }}>主体</label>
                  <Space.Compact style={{ width: '100%', marginTop: 6 }}>
                    <div style={{ width: 150 }}>
                      <ObjectTypeSelect id="pg-subj-type" value={subjectType} onChange={setSubjectType} exclude={SUBJECT_TYPES} />
                    </div>
                    <Input aria-label="主体 id" placeholder="主体 id" value={subjectId} onChange={(e) => setSubjectId(e.target.value)} />
                  </Space.Compact>
                  {myId && (
                    <Button size="small" type="link" style={{ paddingLeft: 0 }} onClick={() => { setSubjectType('user'); setSubjectId(myId) }}>
                      用我自己({myId.slice(0, 8)}…)
                    </Button>
                  )}
                </div>
              )}
              <div>
                <label htmlFor="pg-res-type" style={{ fontWeight: 600 }}>资源</label>
                <Space.Compact style={{ width: '100%', marginTop: 6 }}>
                  <div style={{ width: 150 }}>
                    <ObjectTypeSelect id="pg-res-type" value={resourceType} onChange={(v) => { setResourceType(v); setPermission(undefined) }} exclude={['user']} />
                  </div>
                  {(mode === 'check' || mode === 'subjects') && (
                    <Input aria-label="资源 id" placeholder="资源 id" value={resourceId} onChange={(e) => setResourceId(e.target.value)} />
                  )}
                </Space.Compact>
              </div>
              <div>
                <label htmlFor="pg-perm" style={{ fontWeight: 600 }}>权限(可判定)</label>
                <div style={{ marginTop: 6 }}>
                  <PermissionSelect id="pg-perm" resourceType={resourceType} value={permission} onChange={setPermission} />
                </div>
              </div>
              <Button type="primary" size="large" block onClick={run} loading={running}>
                运行
              </Button>
            </Space>
          </Card>
        </Col>

        <Col xs={24} lg={14}>
          <Card title="结果" style={{ minHeight: 360 }}>
            {/* 判定 Check —— 每模式独立 idle/loading/error/data */}
            {mode === 'check' &&
              (check.isPending ? (
                <PageSkeleton rows={4} />
              ) : check.isError ? (
                <ErrorState message={humanizeError(check.error)} onRetry={run} />
              ) : check.data ? (
                <>
                  <Typography.Paragraph type="secondary" className="mono" style={{ fontSize: 12, marginBottom: 8 }}>
                    {subjectType}:{subjectId} · 权限 {permission} · {resourceType}:{resourceId}
                  </Typography.Paragraph>
                  <AllowDenyResult allowed={check.data.allowed} />
                  <Card size="small" title="判定路径(展开)" style={{ marginTop: 8 }}>
                    {expand.isPending ? (
                      <Spin />
                    ) : expand.isError ? (
                      <Alert type="warning" showIcon message="判定路径加载失败(不影响判定结果)" />
                    ) : expand.data ? (
                      <>
                        <div className="scroll-x">
                          <Tree treeData={expandToTree(expand.data)} defaultExpandAll selectable={false} />
                        </div>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          展示该权限的全部授权路径;在叶子里找到目标主体即为“因何 allow”。
                        </Typography.Text>
                      </>
                    ) : (
                      <Typography.Text type="secondary">无</Typography.Text>
                    )}
                  </Card>
                </>
              ) : (
                <EmptyState description="填好主体 + 权限 + 资源,点“运行”查看判定" />
              ))}

            {/* 反查资源 */}
            {mode === 'resources' &&
              (lookRes.isPending ? (
                <PageSkeleton rows={4} />
              ) : lookRes.isError ? (
                <ErrorState message={humanizeError(lookRes.error)} onRetry={run} />
              ) : lookRes.data ? (
                <List
                  size="small"
                  bordered
                  header={`可访问 ${resourceType}(${lookRes.data.resourceIds.length})`}
                  dataSource={lookRes.data.resourceIds}
                  renderItem={(id) => <List.Item><RefBadge type={resourceType} id={id} /></List.Item>}
                  locale={{ emptyText: '查询完成,无匹配' }}
                />
              ) : (
                <EmptyState description="填好主体 + 权限,点“运行”反查资源" />
              ))}

            {/* 反查主体 */}
            {mode === 'subjects' &&
              (lookSub.isPending ? (
                <PageSkeleton rows={4} />
              ) : lookSub.isError ? (
                <ErrorState message={humanizeError(lookSub.error)} onRetry={run} />
              ) : lookSub.data ? (
                <>
                  <List
                    size="small"
                    bordered
                    header={`有权主体(${lookSub.data.subjects.length})`}
                    dataSource={lookSub.data.subjects}
                    renderItem={(s) => <List.Item><RefBadge type={s.type} id={s.id} /></List.Item>}
                    locale={{ emptyText: '查询完成,无匹配' }}
                  />
                  <Alert
                    type="warning"
                    showIcon
                    style={{ marginTop: 8 }}
                    message="反查暂不过滤条件(caveated)命中,条件命中会被当作确定允许,请结合直接判定核对。"
                  />
                </>
              ) : (
                <EmptyState description="填好资源 + 权限,点“运行”反查主体" />
              ))}
          </Card>
        </Col>
      </Row>
    </>
  )
}
