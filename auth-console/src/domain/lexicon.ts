// 对象类型 / 权限 / 关系 的中文标签 + 配色(单一来源)。基于 knowledge.zed + his.zed。
// relation(可授予,写 grant 用) 与 permission(可判定,check/lookup 用)是两套词汇,UI 必须分清。

export type ObjectType =
  | 'user' | 'group' | 'organization'
  | 'space' | 'folder' | 'document'
  | 'dept' | 'patient' | 'encounter'

export const OBJECT_TYPES: { value: ObjectType; label: string; color: string }[] = [
  { value: 'user', label: '用户', color: 'default' },
  { value: 'group', label: '用户组', color: 'geekblue' },
  { value: 'organization', label: '组织', color: 'purple' },
  { value: 'space', label: '空间/知识库', color: 'cyan' },
  { value: 'folder', label: '文件夹', color: 'gold' },
  { value: 'document', label: '文档', color: 'blue' },
  { value: 'dept', label: '科室', color: 'green' },
  { value: 'patient', label: '患者', color: 'magenta' },
  { value: 'encounter', label: '就诊/病历', color: 'volcano' },
]

export const RELATIONS: Record<string, { label: string; color: string }> = {
  owner: { label: '所有者', color: 'red' },
  admin: { label: '管理员', color: 'volcano' },
  editor: { label: '编辑者', color: 'blue' },
  commenter: { label: '评论者', color: 'lime' },
  viewer: { label: '查看者', color: 'green' },
  member: { label: '成员', color: 'geekblue' },
  head: { label: '科主任', color: 'volcano' },
  attending: { label: '主治医生', color: 'magenta' },
  author: { label: '作者', color: 'blue' },
  public_viewer: { label: '公开', color: 'gold' },
  parent_org: { label: '所属组织', color: 'default' },
  parent_space: { label: '所属空间', color: 'default' },
  parent_folder: { label: '所属文件夹', color: 'default' },
  dept: { label: '所属科室', color: 'default' },
  subject: { label: '关联患者', color: 'default' },
}

export const PERMISSIONS: Record<string, { label: string; color: string }> = {
  view: { label: '查看', color: 'green' },
  comment: { label: '评论', color: 'lime' },
  edit: { label: '编辑', color: 'blue' },
  manage: { label: '管理', color: 'volcano' },
  membership: { label: '成员资格', color: 'geekblue' },
  administrate: { label: '管理组织', color: 'volcano' },
  access: { label: '本科室', color: 'green' },
  care: { label: '主治', color: 'magenta' },
}

// 依对象类型给可授予关系 / 可判权限(静态 from schema;M5 会从 /admin/schema 动态派生)
const RELATIONS_FOR: Record<ObjectType, string[]> = {
  user: [],
  group: ['member'],
  organization: ['admin', 'member'],
  space: ['owner', 'admin', 'editor', 'commenter', 'viewer', 'public_viewer', 'parent_org'],
  folder: ['editor', 'viewer', 'parent_space', 'parent_folder'],
  document: ['owner', 'editor', 'commenter', 'viewer', 'public_viewer', 'parent_space', 'parent_folder'],
  dept: ['member', 'head'],
  patient: ['attending'],
  encounter: ['dept', 'subject', 'author'],
}

const PERMISSIONS_FOR: Record<ObjectType, string[]> = {
  user: [],
  group: ['membership'],
  organization: ['administrate'],
  space: ['manage', 'edit', 'comment', 'view'],
  folder: ['edit', 'view'],
  document: ['edit', 'comment', 'view'],
  dept: ['access'],
  patient: ['care'],
  encounter: ['view', 'edit'],
}

export const relationsFor = (t: ObjectType): string[] => RELATIONS_FOR[t] ?? []
export const permissionsFor = (t: ObjectType): string[] => PERMISSIONS_FOR[t] ?? []
export const objectLabel = (t: string): string => OBJECT_TYPES.find((o) => o.value === t)?.label ?? t
