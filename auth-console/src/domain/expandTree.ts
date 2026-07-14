import type { DataNode } from 'antd/es/tree'

// SpiceDB PermissionRelationshipTree → antd Tree 数据。
interface SpiceNode {
  expandedObject?: { objectType: string; objectId: string }
  expandedRelation?: string
  intermediate?: { operation?: string; children?: SpiceNode[] }
  leaf?: { subjects?: { object?: { objectType: string; objectId: string }; optionalRelation?: string }[] }
}

const OP: Record<string, string> = {
  OPERATION_UNION: '∪ 并',
  OPERATION_INTERSECTION: '∩ 交',
  OPERATION_EXCLUSION: '∖ 差',
}

let counter = 0

function toNode(n: SpiceNode): DataNode {
  const obj = n.expandedObject
  const op = n.intermediate?.operation ? `  (${OP[n.intermediate.operation] ?? n.intermediate.operation})` : ''
  const title = `${obj?.objectType ?? '?'}:${obj?.objectId ?? '?'}#${n.expandedRelation ?? '?'}${op}`
  const key = `n${counter++}`

  if (n.leaf) {
    return {
      key,
      title,
      children: (n.leaf.subjects ?? []).map((s) => ({
        key: `n${counter++}`,
        title: `${s.object?.objectType}:${s.object?.objectId}${s.optionalRelation ? '#' + s.optionalRelation : ''}`,
        isLeaf: true,
      })),
    }
  }
  if (n.intermediate) {
    return { key, title, children: (n.intermediate.children ?? []).map(toNode) }
  }
  return { key, title, isLeaf: true }
}

export function expandToTree(resp: unknown): DataNode[] {
  counter = 0
  const root = (resp as { treeRoot?: SpiceNode })?.treeRoot
  return root ? [toNode(root)] : []
}
