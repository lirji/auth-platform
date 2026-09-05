#!/usr/bin/env node

import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const scriptDir = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(scriptDir, '..')
const portsFile = process.env.PLATFORM_PORTS_FILE || path.join(scriptDir, 'platform-ports.env')
const catalogFile = process.env.PLATFORM_CATALOG_FILE
  || path.join(repoRoot, 'project-portal/public/config/catalog.json')
const checkOnly = process.argv.includes('--check')

const bindings = [
  ['langchain4j', 'LANGCHAIN4J_UI_PORT'],
  ['recsys', 'RECSYS_UI_PORT'],
  ['drools', 'DROOLS_UI_PORT'],
  ['risk', 'RISK_UI_PORT'],
  ['workflow', 'WORKFLOW_UI_PORT'],
  ['reconciliation', 'RECON_UI_PORT'],
  ['benefit', 'BENEFIT_UI_PORT'],
  ['marketing', 'MARKETING_UI_PORT'],
]

function fail(message) {
  process.stderr.write(`中央端口同步失败: ${message}\n`)
  process.exit(1)
}

function readPorts() {
  const values = new Map()
  const allowed = new Set(['AUTH_PORTAL_UI_PORT', ...bindings.map(([, key]) => key)])
  for (const [lineNumber, rawLine] of fs.readFileSync(portsFile, 'utf8').split(/\r?\n/).entries()) {
    const line = rawLine.trim()
    if (!line || line.startsWith('#')) continue
    const match = /^([A-Z][A-Z0-9_]*)=(\d+)$/.exec(line)
    if (!match) fail(`${portsFile}:${lineNumber + 1} 不是 KEY=整数`)
    const [, key, rawPort] = match
    if (!allowed.has(key)) fail(`${portsFile}:${lineNumber + 1} 包含未知配置项 ${key}`)
    if (values.has(key)) fail(`${portsFile}:${lineNumber + 1} 重复定义 ${key}`)
    const port = Number(rawPort)
    if (port < 1 || port > 65535) fail(`${key} 超出 1-65535`)
    values.set(key, port)
  }
  for (const key of allowed) {
    if (!values.has(key)) fail(`缺少 ${key}`)
  }
  const seen = new Map()
  for (const [key, port] of values) {
    if (seen.has(port)) fail(`端口 ${port} 被 ${seen.get(port)} 与 ${key} 重复使用`)
    seen.set(port, key)
  }
  return values
}

function replaceProjectPorts(source, id, port) {
  const marker = `"id": "${id}"`
  const start = source.indexOf(marker)
  if (start < 0) fail(`catalog 缺少项目 ${id}`)
  const nextStart = source.indexOf('\n    {\n      "id": ', start + marker.length)
  const end = nextStart < 0 ? source.length : nextStart
  const before = source.slice(0, start)
  const project = source.slice(start, end)
  const after = source.slice(end)
  const replaced = project.replace(/http:\/\/localhost:\d+/g, `http://localhost:${port}`)
  if (replaced === project && !project.includes(`http://localhost:${port}`)) {
    fail(`项目 ${id} 没有 localhost 入口可同步`)
  }
  return before + replaced + after
}

const ports = readPorts()
const original = fs.readFileSync(catalogFile, 'utf8')
let synchronized = original
for (const [id, key] of bindings) synchronized = replaceProjectPorts(synchronized, id, ports.get(key))

let catalog
try {
  catalog = JSON.parse(synchronized)
} catch (error) {
  fail(`同步后 catalog 不是合法 JSON: ${error.message}`)
}
for (const [id, key] of bindings) {
  const project = catalog.projects.find((item) => item.id === id)
  if (!project) fail(`同步后 catalog 缺少项目 ${id}`)
  for (const field of ['launchUrl', 'healthUrl']) {
    if (!project[field]) continue
    const actual = Number(new URL(project[field]).port)
    if (actual !== ports.get(key)) fail(`${id}.${field}=${actual}，预期 ${ports.get(key)}`)
  }
}

if (checkOnly && synchronized !== original) {
  fail(`catalog 与 ${path.basename(portsFile)} 不一致；运行 deploy/platform-ports.sh sync`)
}
if (!checkOnly && synchronized !== original) fs.writeFileSync(catalogFile, synchronized)
process.stdout.write(checkOnly ? 'catalog 端口与中央注册表一致\n' : 'catalog 端口同步完成\n')
