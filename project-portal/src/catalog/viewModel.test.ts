import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import type { ProjectEntry } from './types.ts'
import { filterProjects, projectLinkAttributes } from './viewModel.ts'

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
  displayHost: 'ai.example.com',
  openMode: 'new-tab',
  order: 1,
  ...overrides,
})

describe('portal view model', () => {
  it('搜索名称、能力、标签和域名，并叠加类别', () => {
    const projects = [project(), project({ id: 'rules', name: '规则平台', category: '规则', capabilities: ['Drools'], tags: [] })]
    assert.deepEqual(filterProjects(projects, 'agent', '全部').map((item) => item.id), ['ai'])
    assert.deepEqual(filterProjects(projects, 'drools', '规则').map((item) => item.id), ['rules'])
    assert.deepEqual(filterProjects(projects, 'ai.example', 'AI').map((item) => item.id), ['ai'])
  })

  it('available 新标签链接包含 noopener/noreferrer 且不改写 URL', () => {
    assert.deepEqual(projectLinkAttributes(project()), {
      href: 'https://ai.example.com/login',
      target: '_blank',
      rel: 'noopener noreferrer',
      ariaLabel: '在新标签页进入 AI 平台',
    })
  })

  it('非 available 不生成链接', () => {
    assert.equal(projectLinkAttributes(project({ status: 'maintenance' })), null)
    assert.equal(projectLinkAttributes(project({ status: 'coming-soon', launchUrl: undefined })), null)
  })
})
