import { Tag } from 'antd'
import { OBJECT_TYPES } from '../../domain/lexicon'

export function RefBadge({ type, id, relation }: { type: string; id: string; relation?: string }) {
  const color = OBJECT_TYPES.find((o) => o.value === type)?.color ?? 'default'
  return (
    <Tag color={color} style={{ fontFamily: 'monospace', maxWidth: '100%', whiteSpace: 'normal', wordBreak: 'break-all', lineHeight: 1.7 }}>
      {type}:{id}
      {relation ? `#${relation}` : ''}
    </Tag>
  )
}

/** 完整关系元组的等宽渲染: resource#relation@subject 。长内容换行不撑破布局。 */
export function TupleText({ text }: { text: string }) {
  return <code className="mono" style={{ fontSize: 13 }}>{text}</code>
}
