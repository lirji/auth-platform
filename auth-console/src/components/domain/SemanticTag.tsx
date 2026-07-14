import { Tag, Tooltip } from 'antd'
import { PERMISSIONS, RELATIONS } from '../../domain/lexicon'

/**
 * relation 与 permission 的视觉区分——不只靠颜色:
 * 关系(可授予)= 虚线描边 + "关系·"前缀;权限(可判定)= 实心填充 + "权限·"前缀。
 */
export function RelationTag({ name }: { name: string }) {
  const meta = RELATIONS[name]
  return (
    <Tooltip title="关系(可授予)">
      <Tag color={meta?.color ?? 'blue'} style={{ borderStyle: 'dashed' }}>
        关系·{meta?.label ?? name}
      </Tag>
    </Tooltip>
  )
}

export function PermissionTag({ name }: { name: string }) {
  const meta = PERMISSIONS[name]
  return (
    <Tooltip title="权限(可判定)">
      <Tag bordered={false} color={meta?.color ?? 'green'}>
        权限·{meta?.label ?? name}
      </Tag>
    </Tooltip>
  )
}
