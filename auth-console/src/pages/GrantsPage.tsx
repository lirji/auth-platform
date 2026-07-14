import { useMemo, useState } from 'react'
import { App, Button, Card, Col, Input, List, Popconfirm, Row, Segmented, Space, Switch, Typography } from 'antd'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ObjectTypeSelect, RelationSelect } from '../components/domain/selects'
import { TupleText } from '../components/domain/RefBadge'
import { PageHeader } from '../components/layout/PageHeader'
import { ErrorState } from '../components/common/AsyncState'
import { colors } from '../theme/colors'
import type { ObjectType } from '../domain/lexicon'
import { listRelationships } from '../api/authz'
import { humanizeError, useGrant, useRevoke } from '../hooks/useAuthz'

export default function GrantsPage() {
  const { message } = App.useApp()
  const qc = useQueryClient()
  const [mode, setMode] = useState<'grant' | 'revoke'>('grant')
  const [resourceType, setResourceType] = useState<ObjectType>('space')
  const [resourceId, setResourceId] = useState('')
  const [relation, setRelation] = useState<string>()
  const [subjectType, setSubjectType] = useState<ObjectType>('user')
  const [subjectId, setSubjectId] = useState('')
  const [userset, setUserset] = useState(true)

  const grant = useGrant()
  const revoke = useRevoke()
  const busy = grant.isPending || revoke.isPending

  const isGroupSubject = subjectType === 'group' || subjectType === 'organization'
  const subjectRelation = isGroupSubject && userset ? 'member' : undefined

  const relKey = ['relationships', resourceType, resourceId] as const
  const rels = useQuery({ queryKey: relKey, queryFn: () => listRelationships(resourceType, resourceId), enabled: !!resourceId })

  const tuple = useMemo(() => {
    const subj = `${subjectType}:${subjectId || '?'}${subjectRelation ? '#' + subjectRelation : ''}`
    return `${resourceType}:${resourceId || '?'}#${relation ?? '?'}@${subj}`
  }, [resourceType, resourceId, relation, subjectType, subjectId, subjectRelation])

  const onResourceType = (v: ObjectType) => {
    setResourceType(v)
    setRelation(undefined)
  }

  const afterWrite = () => qc.invalidateQueries({ queryKey: relKey })

  const submit = () => {
    if (!resourceId || !relation || !subjectId) return message.warning('资源 id、关系、主体 id 都要填')
    const body = { resourceType, resourceId, relation, subjectType, subjectId, subjectRelation }
    const m = mode === 'grant' ? grant : revoke
    m.mutate(body, {
      onSuccess: (d) => {
        message.success(`${mode === 'grant' ? '已授予' : '已撤销'} · ${String(d.token).slice(0, 14)}…`)
        afterWrite()
      },
      onError: (e) => message.error(humanizeError(e)),
    })
  }

  return (
    <>
      <PageHeader title="授予管理" description="给用户/组授予 space/folder/document 等资源的关系(可授予),即写入 SpiceDB 关系元组" />
      <Row gutter={16}>
        <Col xs={24} lg={14}>
          <Card>
            <Segmented
              value={mode}
              onChange={(v) => setMode(v as 'grant' | 'revoke')}
              options={[
                { label: '授予 Grant', value: 'grant' },
                { label: '撤销 Revoke', value: 'revoke' },
              ]}
              style={{ marginBottom: 20 }}
            />
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              <div>
                <label htmlFor="grant-res-type" style={{ fontWeight: 600 }}>资源</label>
                <Space.Compact style={{ width: '100%', marginTop: 6 }}>
                  <div style={{ width: 160 }}>
                    <ObjectTypeSelect id="grant-res-type" value={resourceType} onChange={onResourceType} exclude={['user']} />
                  </div>
                  <Input aria-label="资源 id" placeholder="资源 id(如 acme_kb;带租户前缀)" value={resourceId} onChange={(e) => setResourceId(e.target.value)} />
                </Space.Compact>
              </div>
              <div>
                <label htmlFor="grant-relation" style={{ fontWeight: 600 }}>关系 / 角色(可授予)</label>
                <div style={{ marginTop: 6 }}>
                  <RelationSelect id="grant-relation" resourceType={resourceType} value={relation} onChange={setRelation} />
                </div>
              </div>
              <div>
                <label htmlFor="grant-subj-type" style={{ fontWeight: 600 }}>主体</label>
                <Space.Compact style={{ width: '100%', marginTop: 6 }}>
                  <div style={{ width: 160 }}>
                    <ObjectTypeSelect id="grant-subj-type" value={subjectType} onChange={setSubjectType} exclude={['space', 'folder', 'document', 'dept', 'patient', 'encounter']} />
                  </div>
                  <Input aria-label="主体 id" placeholder="主体 id(user = Casdoor 用户 id)" value={subjectId} onChange={(e) => setSubjectId(e.target.value)} />
                </Space.Compact>
                {isGroupSubject && (
                  <div style={{ marginTop: 8 }}>
                    <Switch checked={userset} onChange={setUserset} size="small" aria-label="作为集合成员 #member" />{' '}
                    <Typography.Text type="secondary">作为集合成员(#member;组/组织授权必须开)</Typography.Text>
                  </div>
                )}
              </div>
              <Card size="small" style={{ background: colors.bgSubtle, borderColor: colors.border }}>
                <Typography.Text type="secondary">即将写入的关系元组:</Typography.Text>
                <div style={{ marginTop: 4 }}>
                  <TupleText text={tuple} />
                </div>
              </Card>
              <Button type="primary" size="large" danger={mode === 'revoke'} loading={busy} onClick={submit} block>
                {mode === 'grant' ? '授予' : '撤销'}
              </Button>
            </Space>
          </Card>
        </Col>

        <Col xs={24} lg={10}>
          <Card title={resourceId ? `${resourceType}:${resourceId} 现存授予` : '现存授予'}>
            {rels.isError ? (
              <ErrorState message={humanizeError(rels.error)} onRetry={() => rels.refetch()} />
            ) : (
            <List
              size="small"
              loading={rels.isLoading}
              locale={{ emptyText: resourceId ? '无' : '填入资源 id 后自动加载' }}
              dataSource={rels.data ?? []}
              renderItem={(r) => (
                <List.Item
                  actions={[
                    <Popconfirm
                      key="rv"
                      title="确认撤销该授予?"
                      okText="撤销"
                      okButtonProps={{ danger: true }}
                      onConfirm={() =>
                        revoke.mutate(
                          {
                            resourceType: r.resource.type,
                            resourceId: r.resource.id,
                            relation: r.relation,
                            subjectType: r.subject.type,
                            subjectId: r.subject.id,
                            subjectRelation: r.subject.relation ?? undefined,
                          },
                          { onSuccess: () => { message.success('已撤销'); afterWrite() }, onError: (e) => message.error(humanizeError(e)) },
                        )
                      }
                    >
                      <Button size="small" danger type="link">撤销</Button>
                    </Popconfirm>,
                  ]}
                >
                  <TupleText text={`#${r.relation}@${r.subject.type}:${r.subject.id}${r.subject.relation ? '#' + r.subject.relation : ''}`} />
                </List.Item>
              )}
            />
            )}
          </Card>
        </Col>
      </Row>
    </>
  )
}
