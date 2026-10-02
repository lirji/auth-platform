import { Button, Empty, Modal, Tag, Tooltip } from 'antd'
import type { ModalProps } from 'antd'
import type { ReactNode } from 'react'
import { executionLabels } from './codes'

interface GovernanceModalProps extends Omit<ModalProps, 'footer'> {
  footer?: ReactNode
  titleActions?: ReactNode
  closeDisabled?: boolean
}

/** 弹层统一标题与固定操作区；关闭仍经过调用方校验，未知结果时禁用所有关闭入口。 */
export function GovernanceModal({ title, titleActions, footer, closeDisabled = false, rootClassName, ...props }: GovernanceModalProps) {
  return <Modal centered width={720} maskClosable={false} destroyOnHidden {...props}
    keyboard={!closeDisabled} closable={!closeDisabled}
    rootClassName={`governance-overlay ${rootClassName ?? ''}`}
    title={<div className="g-modal-heading"><span>{title}</span>{titleActions && <div className="g-modal-title-actions">{titleActions}</div>}</div>}
    footer={<div className="g-modal-actions"><Button disabled={closeDisabled} onClick={props.onCancel}>关闭</Button>{footer}</div>} />
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
