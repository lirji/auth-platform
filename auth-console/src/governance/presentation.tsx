import { Drawer, Empty, Tag, Tooltip } from 'antd'
import type { DrawerProps } from 'antd'
import { executionLabels } from './codes'

/** 复用同一AntD抽屉，仅限定治理样式；保留调用方的关闭/未知结果保护。 */
export function GovernanceDrawer(props: DrawerProps) {
  return <Drawer {...props} rootClassName={`governance-overlay ${props.rootClassName ?? ''}`} />
}

/** 表格只收起展示；完整能力保持在原授权详情，不合并不同来源。 */
export function CapabilityList({ values, compact = false }: { values: string[]; compact?: boolean }) {
  const shown = compact ? values.slice(0, 2) : values
  return <div className="g-capabilities">{shown.map(value => <span key={value} className="g-capability">{value}</span>)}{compact && values.length > 2 && <Tooltip title={values.slice(2).join('、')}><span className="g-capability-more" tabIndex={0}>另 {values.length - 2} 项</span></Tooltip>}{!values.length && <span>无</span>}</div>
}

const shortLabels: Record<string, string> = { ...executionLabels, ACTIVE: '投影已确认', GROUP_CHECK_REQUIRED: '待实时组校验', SUBMITTED: '已提交', PENDING: '待生效' }
/** 颜色仅辅助识别，文字保留审批与投影区别；未知值原样显示。 */
export function PermissionStatus({ state, explanation }: { state: string; explanation?: string }) {
  const color = ['ACTIVE'].includes(state) ? 'success' : ['APPLY_FAILED', 'BLOCKED'].includes(state) ? 'error' : ['PENDING', 'PENDING_APPLY', 'REVOKING'].includes(state) ? 'warning' : undefined
  return <Tooltip title={explanation}><Tag color={color}>{shortLabels[state] ?? state}</Tag></Tooltip>
}

export function GovernanceEmpty({ title, description }: { title: string; description: string }) {
  return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={<div className="g-table-empty"><strong>{title}</strong><p>{description}</p></div>} />
}
