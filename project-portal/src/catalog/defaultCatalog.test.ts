import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { describe, it } from 'node:test'
import { resolve } from 'node:path'

interface DefaultCatalog {
  projects: Array<{ id: string; launchUrl: string; healthUrl: string }>
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
    it(`${label}的四个正式入口都先进入目标项目租户页，不预选或自动登录`, () => {
      assert.deepEqual(catalog.projects.map(({ id }) => id), ['langchain4j', 'recsys', 'drools', 'risk'])

      for (const project of catalog.projects) {
        const url = new URL(project.launchUrl)
        const healthUrl = new URL(project.healthUrl)
        assert.equal(url.searchParams.has('auto'), false, `${project.id} 不应携带 auto`)
        assert.equal(url.searchParams.has('tenant'), false, `${project.id} 不应预选 tenant`)
        assert.equal(url.searchParams.has('clientId'), false, `${project.id} 不应暴露固定 clientId`)
        assert.match(url.pathname, /\/login$/, `${project.id} 必须指向目标项目登录页`)
        assert.equal(healthUrl.origin, url.origin, `${project.id} healthUrl 必须与 launchUrl 同源`)
      }
    })

    it(`${label}保留的回跳参数都是目标项目站内路径`, () => {
      for (const project of catalog.projects) {
        const url = new URL(project.launchUrl)
        const returnTo = url.searchParams.get('returnTo') ?? url.searchParams.get('redirect')
        assert.ok(returnTo?.startsWith('/') && !returnTo.startsWith('//'), `${project.id} 回跳必须是站内路径`)
      }
    })
  }
})
