import { apiClient } from './client'
import type { Page, Partition } from './governance'
import { validateReviewDetail, validateReviewList, validateReviewResponsibles, type ReviewAction, type ReviewActionKind, type ReviewCreate, type ReviewDetail, type ReviewResponsible, type ReviewSummary } from '../governance/accessReview'

const base = '/api/governance/v1/access/reviews'
/** 复核客户端绑定完整分区；状态缺失不是空数据或成功。 */
export async function reviewList(p: Partition, member?: string, after?: string, signal?: AbortSignal) {
  return validateReviewList((await apiClient.get<Page<ReviewSummary>>(base, { params: { ...p, membership_id: member, after }, signal })).data)
}
export async function reviewDetail(p: Partition, id: string, signal?: AbortSignal) {
  return validateReviewDetail((await apiClient.get<ReviewDetail>(`${base}/${encodeURIComponent(id)}`, { params: p, signal })).data, id)
}
export async function reviewResponsibles(p: Partition, signal?: AbortSignal) {
  return validateReviewResponsibles((await apiClient.get<ReviewResponsible[]>('/api/governance/v1/access/review-responsibles', { params: p, signal })).data)
}
/** 超时后必须重用原body和command，不重新扩充所选来源。 */
export async function createReview(body: ReviewCreate) {
  return validateReviewDetail((await apiClient.post<ReviewDetail>(base, body)).data)
}
export async function actReview(id: string, kind: ReviewActionKind, body: ReviewAction) {
  return validateReviewDetail((await apiClient.post<ReviewDetail>(`${base}/${encodeURIComponent(id)}/${kind}`, body)).data, id)
}
