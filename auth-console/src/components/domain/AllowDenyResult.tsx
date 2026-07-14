import { CheckCircleFilled, CloseCircleFilled } from '@ant-design/icons'
import { Result } from 'antd'
import { colors } from '../../theme/colors'

/** 判定结果大卡。只接 allowed,不调用 check。 */
export function AllowDenyResult({ allowed }: { allowed: boolean }) {
  return allowed ? (
    <Result icon={<CheckCircleFilled style={{ color: colors.success }} />} status="success" title="允许 · ALLOW" />
  ) : (
    <Result icon={<CloseCircleFilled style={{ color: colors.error }} />} status="error" title="拒绝 · DENY" />
  )
}
