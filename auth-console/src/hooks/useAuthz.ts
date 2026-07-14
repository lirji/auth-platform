import { useMutation } from '@tanstack/react-query'
import * as api from '../api/authz'

export const useGrant = () => useMutation({ mutationFn: api.grant })
export const useRevoke = () => useMutation({ mutationFn: api.revoke })
export const useCheck = () => useMutation({ mutationFn: api.check })

export const useLookupSubjects = () =>
  useMutation({
    mutationFn: (v: { resourceType: string; resourceId: string; permission: string; subjectType: string }) =>
      api.lookupSubjects(v.resourceType, v.resourceId, v.permission, v.subjectType),
  })

export const useLookupResources = () =>
  useMutation({
    mutationFn: (v: { subjectType: string; subjectId: string; permission: string; resourceType: string }) =>
      api.lookupResources(v.subjectType, v.subjectId, v.permission, v.resourceType),
  })

export const useCasdoorSync = () => useMutation({ mutationFn: api.casdoorSync })

export const useExpand = () =>
  useMutation({
    mutationFn: (v: { resourceType: string; resourceId: string; permission: string }) =>
      api.expand(v.resourceType, v.resourceId, v.permission),
  })

/** 统一把后端错误转人话。 */
export function humanizeError(e: unknown): string {
  const err = e as { response?: { status?: number }; message?: string }
  const s = err?.response?.status
  if (s === 401) return '登录已过期,请重新登录'
  if (s === 403) return '无权限(需 authz-admin)'
  if (s === 409) return '功能未启用(如 Casdoor 同步)'
  if (s) return `请求失败(${s})`
  return err?.message ?? '网络错误'
}
