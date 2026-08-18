import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'
import { resolve } from 'node:path'

interface DefaultCatalog {
  projects: Array<{
    id: string
    status: 'available' | 'maintenance' | 'coming-soon'
    launchUrl?: string
    healthUrl?: string
  }>
}

describe('项目目录登录契约', () => {
  const catalogs = [
    ['本地默认目录', 'public/config/catalog.json'],
    ['生产配置示例', 'config/catalog.example.json'],
  ].map(([label, path]) => ({
    label,
    catalog: JSON.parse(readFileSync(resolve(process.cwd(), path), 'utf8')) as DefaultCatalog,
  }))

  for (const { label, catalog } of catalogs) {
    it(`${label}包含六个正式项目，已开放项目都从目标项目登录页进入`, () => {
      assert.deepEqual(
        catalog.projects.map(({ id }) => id),
        ['langchain4j', 'recsys', 'drools', 'risk', 'workflow', 'reconciliation'],
      )

      for (const project of catalog.projects.filter(({ status }) => status === 'available')) {
        assert.ok(project.launchUrl, `${project.id} 必须配置 launchUrl`)
        const url = new URL(project.launchUrl)
        assert.equal(url.searchParams.has('auto'), false, `${project.id} 不应携带 auto`)
        assert.equal(url.searchParams.has('tenant'), false, `${project.id} 不应预选 tenant`)
        assert.equal(url.searchParams.has('clientId'), false, `${project.id} 不应暴露固定 clientId`)
        assert.match(url.pathname, /\/login$/, `${project.id} 必须指向目标项目登录页`)
        if (project.healthUrl) {
          const healthUrl = new URL(project.healthUrl)
          assert.equal(healthUrl.origin, url.origin, `${project.id} healthUrl 必须与 launchUrl 同源`)
        }
      }

      const recon = catalog.projects.find(({ id }) => id === 'reconciliation')
      assert.equal(recon?.status, 'coming-soon')
      assert.equal(recon?.launchUrl, undefined)
      assert.equal(recon?.healthUrl, undefined)
    })

    it(`${label}保留的回跳参数都是目标项目站内路径`, () => {
      for (const project of catalog.projects) {
        if (!project.launchUrl) continue
        const url = new URL(project.launchUrl)
        const returnTo = url.searchParams.get('returnTo') ?? url.searchParams.get('redirect')
        if (returnTo !== null) {
          assert.ok(returnTo.startsWith('/') && !returnTo.startsWith('//'), `${project.id} 回跳必须是站内路径`)
        }
      }
    })
  }

  it('本地 Docker 入口与统一端口及专用健康路径一致', () => {
    const local = catalogs[0].catalog
    const localHosts: Record<string, string> = {}
    for (const { id, launchUrl } of local.projects) {
      if (launchUrl) localHosts[id] = new URL(launchUrl).host
    }
    assert.deepEqual(
      localHosts,
      {
        langchain4j: 'localhost:5173',
        recsys: 'localhost:9095',
        drools: 'localhost:8095',
        risk: 'localhost:15173',
        workflow: 'localhost:8302',
      },
    )
    for (const entry of local.projects) {
      if (entry.healthUrl) {
        assert.equal(new URL(entry.healthUrl).pathname, '/healthz', `${entry.id} 必须使用专用健康端点`)
      }
    }
  })
})
