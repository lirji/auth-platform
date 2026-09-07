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

function centralPorts(): Record<string, number> {
  return Object.fromEntries(
    readFileSync(resolve(process.cwd(), '../deploy/platform-ports.env'), 'utf8')
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith('#'))
      .map((line) => {
        const [key, value] = line.split('=')
        return [key, Number(value)]
      }),
  )
}

describe('项目目录入口契约', () => {
  const catalogs = [
    ['本地默认目录', 'public/config/catalog.json'],
    ['生产配置示例', 'config/catalog.example.json'],
  ].map(([label, path]) => ({
    label,
    catalog: JSON.parse(readFileSync(resolve(process.cwd(), path), 'utf8')) as DefaultCatalog,
  }))

  for (const { label, catalog } of catalogs) {
    it(`${label}包含八个正式项目，已开放项目都有安全入口`, () => {
      assert.deepEqual(
        catalog.projects.map(({ id }) => id),
        ['langchain4j', 'recsys', 'drools', 'risk', 'workflow', 'reconciliation', 'benefit', 'marketing'],
      )

      for (const project of catalog.projects.filter(({ status }) => status === 'available')) {
        assert.ok(project.launchUrl, `${project.id} 必须配置 launchUrl`)
        const url = new URL(project.launchUrl)
        assert.equal(url.searchParams.has('auto'), false, `${project.id} 不应携带 auto`)
        assert.equal(url.searchParams.has('tenant'), false, `${project.id} 不应预选 tenant`)
        assert.equal(url.searchParams.has('clientId'), false, `${project.id} 不应暴露固定 clientId`)
        if (project.healthUrl) {
          const healthUrl = new URL(project.healthUrl)
          assert.equal(healthUrl.origin, url.origin, `${project.id} healthUrl 必须与 launchUrl 同源`)
        }
      }

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
    const ports = centralPorts()
    const localHosts: Record<string, string> = {}
    for (const { id, launchUrl } of local.projects) {
      if (launchUrl) localHosts[id] = new URL(launchUrl).host
    }
    assert.deepEqual(
      localHosts,
      {
        langchain4j: `localhost:${ports.LANGCHAIN4J_UI_PORT}`,
        recsys: `localhost:${ports.RECSYS_UI_PORT}`,
        drools: `localhost:${ports.DROOLS_UI_PORT}`,
        risk: `localhost:${ports.RISK_UI_PORT}`,
        workflow: `localhost:${ports.WORKFLOW_UI_PORT}`,
        reconciliation: `localhost:${ports.RECON_UI_PORT}`,
        benefit: `localhost:${ports.BENEFIT_UI_PORT}`,
        marketing: `localhost:${ports.MARKETING_UI_PORT}`,
      },
    )
    for (const entry of local.projects) {
      if (entry.healthUrl) {
        assert.equal(new URL(entry.healthUrl).pathname, '/healthz', `${entry.id} 必须使用专用健康端点`)
      }
    }

    const recon = local.projects.find(({ id }) => id === 'reconciliation')
    assert.equal(recon?.status, 'available')
    assert.equal(recon?.launchUrl, `http://localhost:${ports.RECON_UI_PORT}/login`)
    assert.equal(recon?.healthUrl, `http://localhost:${ports.RECON_UI_PORT}/healthz`)

    const benefit = local.projects.find(({ id }) => id === 'benefit')
    assert.equal(benefit?.status, 'available')
    assert.equal(benefit?.launchUrl, `http://localhost:${ports.BENEFIT_UI_PORT}/login?returnTo=%2Fdashboard`)
    assert.equal(benefit?.healthUrl, `http://localhost:${ports.BENEFIT_UI_PORT}/healthz`)

    const marketing = local.projects.find(({ id }) => id === 'marketing')
    assert.equal(marketing?.status, 'available')
    assert.equal(marketing?.launchUrl, `http://localhost:${ports.MARKETING_UI_PORT}/login?returnTo=%2F`)
    assert.equal(marketing?.healthUrl, `http://localhost:${ports.MARKETING_UI_PORT}/healthz`)
  })

  it('生产示例在正式域名与鉴权未配置前保持未开放', () => {
    const production = catalogs[1].catalog
    const recon = production.projects.find(({ id }) => id === 'reconciliation')
    assert.equal(recon?.status, 'coming-soon')
    assert.equal(recon?.launchUrl, undefined)
    assert.equal(recon?.healthUrl, undefined)

    const benefit = production.projects.find(({ id }) => id === 'benefit')
    assert.equal(benefit?.status, 'coming-soon')
    assert.equal(benefit?.launchUrl, undefined)
    assert.equal(benefit?.healthUrl, undefined)

    const marketing = production.projects.find(({ id }) => id === 'marketing')
    assert.equal(marketing?.status, 'coming-soon')
    assert.equal(marketing?.launchUrl, undefined)
    assert.equal(marketing?.healthUrl, undefined)
  })
})
