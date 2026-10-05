import type { CatalogHistory, CatalogReleaseDetail } from '../api/governance';
const MAX_PAGE = 100;
/** 旧版本来源允许未知；缺页游标和目标混合不能被当成真实空历史。 */
export function validateCatalogHistory(
  value: CatalogHistory,
  application: string,
  before?: number,
): CatalogHistory {
  if (
    !value ||
    !Array.isArray(value.items) ||
    value.items.length > MAX_PAGE ||
    (value.next_before_version !== null &&
      (!Number.isSafeInteger(value.next_before_version) || value.next_before_version < 1)) ||
    value.items.some(
      (item, index) =>
        !item ||
        item.application !== application ||
        !Number.isSafeInteger(item.version) ||
        item.version < 1 ||
        (before !== undefined && item.version >= before) ||
        (index > 0 && item.version >= value.items[index - 1].version),
    ) ||
    (value.next_before_version !== null &&
      value.next_before_version !== value.items[value.items.length - 1]?.version)
  )
    throw new Error('发布历史响应与当前应用或分页依据不一致');
  return value;
}
/** 固定版本详情不可返回当前版本冒充，来源未知与目录读取失败分别展示。 */
export function validateCatalogReleaseDetail(
  value: CatalogReleaseDetail,
  application: string,
  version: number,
): CatalogReleaseDetail {
  if (
    !value ||
    value.release?.application !== application ||
    value.release.version !== version ||
    value.manifest?.application !== application ||
    value.manifest.manifest_version !== version
  )
    throw new Error('发布详情与选定版本不一致');
  return value;
}
