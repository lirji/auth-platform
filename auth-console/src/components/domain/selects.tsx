import { Select } from 'antd'
import {
  OBJECT_TYPES,
  PERMISSIONS,
  RELATIONS,
  permissionsFor,
  relationsFor,
  type ObjectType,
} from '../../domain/lexicon'

export function ObjectTypeSelect({
  value,
  onChange,
  exclude,
  id,
}: {
  value?: ObjectType
  onChange: (v: ObjectType) => void
  exclude?: ObjectType[]
  id?: string
}) {
  return (
    <Select<ObjectType>
      id={id}
      value={value}
      onChange={onChange}
      style={{ width: '100%' }}
      placeholder="对象类型"
      options={OBJECT_TYPES.filter((o) => !exclude?.includes(o.value)).map((o) => ({ value: o.value, label: o.label }))}
    />
  )
}

export function RelationSelect({
  resourceType,
  value,
  onChange,
  id,
}: {
  resourceType?: ObjectType
  value?: string
  onChange: (v: string) => void
  id?: string
}) {
  const options = resourceType
    ? relationsFor(resourceType).map((r) => ({ value: r, label: RELATIONS[r]?.label ?? r }))
    : []
  return (
    <Select
      id={id}
      value={value}
      onChange={onChange}
      style={{ width: '100%' }}
      placeholder="关系/角色(可授予)"
      options={options}
      notFoundContent="该对象类型无可授予关系"
    />
  )
}

export function PermissionSelect({
  resourceType,
  value,
  onChange,
  id,
}: {
  resourceType?: ObjectType
  value?: string
  onChange: (v: string) => void
  id?: string
}) {
  const options = resourceType
    ? permissionsFor(resourceType).map((p) => ({ value: p, label: PERMISSIONS[p]?.label ?? p }))
    : []
  return (
    <Select
      id={id}
      value={value}
      onChange={onChange}
      style={{ width: '100%' }}
      placeholder="权限(可判定)"
      options={options}
    />
  )
}
