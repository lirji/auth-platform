import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import type { ProjectEntry } from './types.ts'
import { probeProjectReachability } from './availability.ts'

const project = (overrides: Partial<ProjectEntry> = {}): ProjectEntry => ({
  id: 'ai',
  name: 'AI 平台',
  summary: '智能对话服务',
  category: 'AI',
  capabilities: ['RAG'],
  tags: ['Agent'],
  icon: 'ai',
  status: 'available',
  launchUrl: 'https://ai.example.com/login',
  healthUrl: 'https://ai.example.com/healthz',
  displayHost: 'ai.example.com',
  openMode: 'new-tab',
  order: 1,
  ...overrides,
})

describe('project reachability', () => {
  it('使用无凭据 no-cors 请求探测目标', async () => {
    let requestedUrl = ''
    let requestedInit: RequestInit | undefined
    const result = await probeProjectReachability(project(), async (url, init) => {
      requestedUrl = url
      requestedInit = init
    })

    assert.equal(result, 'online')
    assert.equal(requestedUrl, 'https://ai.example.com/healthz')
    assert.equal(requestedInit?.mode, 'no-cors')
    assert.equal(requestedInit?.credentials, 'omit')
    assert.equal(requestedInit?.cache, 'no-store')
  })

  it('网络错误和超时判为离线', async () => {
    const failed = await probeProjectReachability(project(), async () => {
      throw new Error('connection refused')
    })
    const timedOut = await probeProjectReachability(project(), (_url, init) => new Promise((_, reject) => {
      init.signal?.addEventListener('abort', () => reject(new Error('aborted')), { once: true })
    }), 5)

    assert.equal(failed, 'offline')
    assert.equal(timedOut, 'offline')
  })

  it('未配置探测地址或配置不可用时不发请求', async () => {
    let calls = 0
    const request = async () => { calls += 1 }
    assert.equal(await probeProjectReachability(project({ healthUrl: undefined }), request), 'unchecked')
    assert.equal(await probeProjectReachability(project({ status: 'maintenance' }), request), 'unchecked')
    assert.equal(calls, 0)
  })
})
