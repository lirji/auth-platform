import { useState } from 'react'
import { App, Button, Card, Input, List, Popconfirm, Space, Tooltip, Typography } from 'antd'
import { useQueryClient } from '@tanstack/react-query'
import { RelationTag } from './SemanticTag'
import { RefBadge } from './RefBadge'
import { ObjectTypeSelect } from './selects'
import { OBJECT_TYPES, type ObjectType } from '../../domain/lexicon'
import type { Relationship } from '../../api/authz'
import { humanizeError, useGrant, useRevoke } from '../../hooks/useAuthz'

// 该角色允许的主体类型:owner 只能 user;其余成员关系 user | group#member。
// ObjectTypeSelect 只吃黑名单 exclude,这里把白名单反转成 exclude。
const ALL_TYPES = OBJECT_TYPES.map((o) => o.value)
const excludeExcept = (allowed: ObjectType[]) => ALL_TYPES.filter((v) => !allowed.includes(v))

const NO_WRITE_TIP = '需 authz-admin 权限'

/**
 * 单角色卡:展示某对象在某 relation 上的直接成员,并就地增删。
 * 自包含 mutation + invalidate;成员权威源是直接关系元组(可精确 revoke)。
 * 只读用户(canWrite=false)写控件禁用 + tooltip(非隐藏),后端仍是权威边界。
 */
export function SpaceMemberCard({
  resourceType,
  resourceId,
  relation,
  members,
  canWrite,
  myId,
}: {
  resourceType: ObjectType
  resourceId: string
  relation: string
  members: Relationship[]
  canWrite: boolean
  myId?: string
}) {
  const { message } = App.useApp()
  const qc = useQueryClient()
  const grant = useGrant()
  const revoke = useRevoke()

  const ownerOnly = relation === 'owner' // owner 主体只能是 user(schema)
  const [subjectType, setSubjectType] = useState<ObjectType>('user')
  const [subjectId, setSubjectId] = useState('')

  const afterWrite = () => qc.invalidateQueries({ queryKey: ['relationships', resourceType, resourceId] })

  const add = () => {
    const id = subjectId.trim()
    if (!id) return message.warning('填入主体 id')
    const type: ObjectType = ownerOnly ? 'user' : subjectType
    // group 主体恒 #member(裸 group 不在 schema 允许的主体类型内,会被 SpiceDB 拒)。
    const subjectRelation = type === 'group' ? 'member' : undefined
    grant.mutate(
      { resourceType, resourceId, relation, subjectType: type, subjectId: id, subjectRelation },
      {
        onSuccess: () => {
          message.success('已添加')
          setSubjectId('')
          afterWrite()
        },
        onError: (e) => message.error(humanizeError(e)),
      },
    )
  }

  const remove = (r: Relationship) => {
    // body 从列表元组逐字段派生,确保精确 DELETE。
    revoke.mutate(
      {
        resourceType: r.resource.type,
        resourceId: r.resource.id,
        relation: r.relation,
        subjectType: r.subject.type,
        subjectId: r.subject.id,
        subjectRelation: r.subject.relation ?? undefined,
      },
      {
        onSuccess: () => {
          message.success('已移除')
          afterWrite()
        },
        onError: (e) => message.error(humanizeError(e)),
      },
    )
  }

  // 自锁警告:移除的是"我自己"的 owner/admin(后端无自锁保护)。
  const isSelfPrivileged = (r: Relationship) =>
    !!myId && r.subject.type === 'user' && r.subject.id === myId && (relation === 'owner' || relation === 'admin')

  const isGroup = !ownerOnly && subjectType === 'group'

  return (
    <Card
      size="small"
      title={
        <Space>
          <RelationTag name={relation} />
          <Typography.Text type="secondary">{members.length}</Typography.Text>
        </Space>
      }
      style={{ marginBottom: 12 }}
    >
      <List
        size="small"
        locale={{ emptyText: '无成员' }}
        dataSource={members}
        renderItem={(r) => (
          <List.Item
            actions={[
              <Popconfirm
                key="rm"
                title={isSelfPrivileged(r) ? `这会移除你自己的「${relation}」权限,确认?` : '确认移除该成员?'}
                okText="移除"
                okButtonProps={{ danger: true }}
                onConfirm={() => remove(r)}
                disabled={!canWrite}
              >
                <Tooltip title={canWrite ? undefined : NO_WRITE_TIP}>
                  <Button size="small" danger type="link" disabled={!canWrite} loading={revoke.isPending}>
                    移除
                  </Button>
                </Tooltip>
              </Popconfirm>,
            ]}
          >
            <RefBadge type={r.subject.type} id={r.subject.id} relation={r.subject.relation ?? undefined} />
          </List.Item>
        )}
      />
      <Space.Compact style={{ width: '100%', marginTop: 8 }}>
        {!ownerOnly && (
          <div style={{ width: 110 }}>
            <ObjectTypeSelect value={subjectType} onChange={setSubjectType} exclude={excludeExcept(['user', 'group'])} />
          </div>
        )}
        <Input
          placeholder={ownerOnly ? 'user id' : isGroup ? 'group id(以 #member 授予)' : '主体 id'}
          value={subjectId}
          onChange={(e) => setSubjectId(e.target.value)}
          onPressEnter={canWrite ? add : undefined}
          disabled={!canWrite}
        />
        <Tooltip title={canWrite ? undefined : NO_WRITE_TIP}>
          <Button type="primary" loading={grant.isPending} onClick={add} disabled={!canWrite}>
            添加
          </Button>
        </Tooltip>
      </Space.Compact>
      {!ownerOnly && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          用户组以 #member 集合成员身份授予。
        </Typography.Text>
      )}
    </Card>
  )
}
