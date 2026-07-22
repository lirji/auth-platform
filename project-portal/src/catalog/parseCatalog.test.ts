import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { parseCatalog } from './parseCatalog.ts'

const project = (overrides: Record<string, unknown> = {}) => ({
  id: 'app-one',
  name: '应用一',
  summary: '公开能力说明',
  category: 'AI 平台',
  capabilities: ['对话', '检索'],
  tags: ['RAG'],
  icon: 'ai',
  status: 'available',
  launchUrl: 'https://app.example.com/login?source=portal',
  openMode: 'new-tab',
  order: 10,
  ...overrides,
})

describe('parseCatalog', () => {
  it('解析、安全投影并稳定排序项目', () => {
    const result = parseCatalog({
      schemaVersion: 1,
      projects: [project({ id: 'b', name: '乙', order: 20 }), project({ id: 'a', name: '甲', order: 10 })],
    })
    assert.deepEqual(result.issues, [])
    assert.deepEqual(result.catalog.projects.map((item) => item.id), ['a', 'b'])
    assert.equal(result.catalog.projects[0].displayHost, 'app.example.com')
  })

  for (const launchUrl of ['javascript:alert(1)', 'data:text/html,bad', 'http://example.com/login', 'https://user:pass@example.com/login']) {
    it(`拒绝危险链接 ${launchUrl}`, () => {
      const result = parseCatalog({ schemaVersion: 1, projects: [project({ launchUrl })] })
      assert.deepEqual(result.catalog.projects, [])
      assert.match(result.issues[0], /launchUrl/)
    })
  }

  it('只在运行时显式允许时接受 loopback HTTP', () => {
    const denied = parseCatalog({ schemaVersion: 1, projects: [project({ launchUrl: 'http://localhost:8093/login' })] })
    const allowed = parseCatalog({ schemaVersion: 1, allowHttpLocalhost: true, projects: [project({ launchUrl: 'http://127.0.0.1:8093/login' })] })
    assert.equal(denied.catalog.projects.length, 0)
    assert.equal(allowed.catalog.projects.length, 1)
  })

  it('忽略 disabled，拒绝重复 id，并保留其它合法项目', () => {
    const result = parseCatalog({
      schemaVersion: 1,
      projects: [project(), project({ enabled: false, id: 'hidden' }), project({ name: '重复项' })],
    })
    assert.deepEqual(result.catalog.projects.map((item) => item.id), ['app-one'])
    assert.ok(result.issues.includes('项目 id 重复: app-one'))
  })

  it('前一个同 id 项字段无效时，不遮蔽后续合法项目', () => {
    const result = parseCatalog({
      schemaVersion: 1,
      projects: [project({ name: '' }), project({ name: '合法项目' })],
    })
    assert.deepEqual(result.catalog.projects.map((item) => item.name), ['合法项目'])
    assert.equal(result.issues.length, 1)
  })

  it('maintenance 可无链接，但有链接时仍必须安全', () => {
    const result = parseCatalog({ schemaVersion: 1, projects: [project({ status: 'maintenance', launchUrl: undefined })] })
    assert.equal(result.catalog.projects[0].launchUrl, undefined)
  })

  it('未知 schema fail-closed', () => {
    assert.throws(() => parseCatalog({ schemaVersion: 2, projects: [] }), /不支持的能力目录版本/)
  })
})
