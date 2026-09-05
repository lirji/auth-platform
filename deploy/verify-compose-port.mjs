#!/usr/bin/env node

import process from 'node:process'

const [service, rawTarget, rawExpected] = process.argv.slice(2)
const target = Number(rawTarget)
const expected = String(rawExpected)
let input = ''
for await (const chunk of process.stdin) input += chunk

const compose = JSON.parse(input)
const ports = compose.services?.[service]?.ports || []
const binding = ports.find((item) => Number(item.target) === target)
if (!binding) {
  process.stderr.write(`服务 ${service} 缺少容器端口 ${target} 的发布映射\n`)
  process.exit(1)
}
if (String(binding.published) !== expected) {
  process.stderr.write(`服务 ${service} 发布端口为 ${binding.published}，中央注册表要求 ${expected}\n`)
  process.exit(1)
}
process.stdout.write(`${service}: ${expected}->${target}\n`)
