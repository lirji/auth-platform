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
  const err = e as { response?: { status?: number; data?: unknown }; message?: string }
  const s = err?.response?.status
  const backend = backendError(err?.response?.data)
  if (s === 401) return '登录已过期,请重新登录'
  if (s === 403) return '无权限（需 authz-admin，或无权进入该项目工作区）'
  if (s === 409) return '功能未启用(如 Casdoor 同步)'
  if (s === 503) return backend ?? '该项目的 SpiceDB 未启动或不可达'
  if (backend) return backend
  if (s) return `请求失败(${s})`
  return err?.message ?? '网络错误'
}

function backendError(data: unknown): string | undefined {
  if (!data || typeof data !== 'object') return undefined
  const rec = data as Record<string, unknown>
  if (typeof rec.error === 'string' && rec.error.trim() && rec.error !== 'Internal Server Error') {
    return rec.error
  }
  if (typeof rec.message === 'string' && rec.message.trim()) return rec.message
}
