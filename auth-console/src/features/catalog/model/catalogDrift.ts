import type {
  CatalogCandidate,
  CatalogDriftReport,
  CatalogDriftState,
} from '../../../api/governance';
export const DriftState = {
  NOT_PUBLISHED: 'NOT_PUBLISHED',
  SOURCE_MISMATCH: 'SOURCE_MISMATCH',
  DISPLAY_MISMATCH: 'DISPLAY_MISMATCH',
  MATCHED_DECLARATION: 'MATCHED_DECLARATION',
  UNKNOWN: 'UNKNOWN',
} as const;
export const driftLabels: Record<CatalogDriftState, string> = {
  NOT_PUBLISHED: '目录尚未发布',
  SOURCE_MISMATCH: '源码声明与发布不一致',
  DISPLAY_MISMATCH: '显示内容不一致',
  MATCHED_DECLARATION: '声明一致',
  UNKNOWN: '依据未知',
};
const HASH = /^[a-f0-9]{64}$/;
/** 不将错目标、缺层次、旧服务或虚构的运行成功展示为已核验。 */
export function validateCatalogDrift(
  value: CatalogDriftReport,
  candidate: CatalogCandidate,
): CatalogDriftReport {
  const source = candidate.source;
  if (
    !value ||
    value.application !== candidate.manifest.application ||
    value.expected_version !== candidate.manifest.manifest_version ||
    !HASH.test(value.content_hash) ||
    !HASH.test(value.presentation_hash) ||
    !Number.isFinite(Date.parse(value.observed_at)) ||
    (source === null
      ? value.declared_source !== null
      : !value.declared_source ||
        source.commit !== value.declared_source.commit ||
        source.artifact_hash !== value.declared_source.artifact_hash) ||
    !Object.values(DriftState).includes(value.state) ||
    value.runtime_state !== DriftState.UNKNOWN ||
    !value.deployment ||
    !Object.values(DriftState).includes(value.deployment.state)
  )
    throw new Error('漂移核对响应与当前声明不一致');
  if (value.published === null) {
    if (value.state !== DriftState.NOT_PUBLISHED || value.diff !== null)
      throw new Error('未发布状态没有完整依据');
  } else if (
    !value.published ||
    value.state === DriftState.NOT_PUBLISHED ||
    value.published.application !== value.application ||
    !Number.isSafeInteger(value.published.version) ||
    value.published.version < 1 ||
    !HASH.test(value.published.content_hash) ||
    !HASH.test(value.published.presentation_hash ?? '') ||
    !value.diff ||
    value.diff.application !== value.application ||
    value.diff.current_version !== value.published.version ||
    value.diff.proposed_version !== value.expected_version ||
    value.diff.content_hash !== value.content_hash ||
    value.diff.presentation_hash !== value.presentation_hash ||
    !Array.isArray(value.diff.menu_changes) ||
    !Array.isArray(value.diff.violations) ||
    !Array.isArray(value.diff.affected_capabilities)
  )
    throw new Error('漂移核对缺少固定发布或具体差异');
  const d = value.deployment.declaration;
  if (d === null) {
    if (value.deployment.state !== DriftState.UNKNOWN)
      throw new Error('未登记的部署声明不能声称一致');
  } else if (
    !d ||
    d.application_id !== value.application ||
    !Number.isSafeInteger(d.manifest_version) ||
    d.manifest_version < 1 ||
    !HASH.test(d.content_hash) ||
    !HASH.test(d.presentation_hash) ||
    !d.source ||
    !/^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(d.source.commit) ||
    !HASH.test(d.source.artifact_hash) ||
    !Number.isFinite(Date.parse(d.declared_at)) ||
    !d.evidence_ref
  )
    throw new Error('受信部署声明缺失或目标不一致');
  return value;
}
