/** 复用仓库既有Playwright入口验证真实React竞态；只启动/停止本测试自己的临时Vite进程。 */
import { spawn } from 'node:child_process';
import { createRequire } from 'node:module';
import net from 'node:net';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const dependency = process.env.PLAYWRIGHT_MODULE || path.resolve(root, '../commerce-platform/frontend/node_modules/@playwright/test');
const { chromium } = createRequire(import.meta.url)(dependency);
const probe = net.createServer();
await new Promise((resolve, reject) => probe.once('error', reject).listen(0, '127.0.0.1', resolve));
const port = probe.address().port;
await new Promise((resolve) => probe.close(resolve));
const vite = spawn(process.execPath, [path.join(root, 'auth-console/node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', String(port), '--strictPort'], { cwd: path.join(root, 'auth-console'), stdio: ['ignore', 'pipe', 'pipe'] });
let logs = '';
vite.stdout.on('data', (data) => { logs = (logs + data.toString()).slice(-4000); });
vite.stderr.on('data', (data) => { logs = (logs + data.toString()).slice(-4000); });
let browser;
try {
  const origin = `http://127.0.0.1:${port}`;
  const deadline = Date.now() + 20_000;
  let ready = false;
  while (Date.now() < deadline && vite.exitCode === null) {
    try { ready = (await fetch(origin + '/healthz')).ok; } catch { /* 限时等待自己新建的测试进程。 */ }
    if (ready) break;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true });
  const failures = [];
  for (const scenario of ['double', 'resetAndRetry']) {
    const page = await browser.newPage();
    await page.goto(origin + '/tests/fixtures/use-command-race.html');
    await page.waitForSelector('output');
    await page.evaluate((scenario) => window.commandRace[scenario](), scenario);
    if (scenario === 'resetAndRetry') {
      await page.waitForFunction(() => window.commandRace.snapshot().unknown);
      await page.evaluate(() => { void window.commandRace.retry(); });
    }
    const result = await page.evaluate(() => window.commandRace.snapshot());
    try {
      assert.equal(result.calls.length, scenario === 'double' ? 1 : 2, '单次在途交换必须互斥');
      assert.equal(result.builders, 1, '在途reset/未知结果重试必须沿用冻结命令');
      assert.ok(result.calls.every((call) => call.commandId === result.calls[0].commandId), '重试不得更换幂等身份');
      await page.evaluate(() => window.commandRace.resolve());
      await page.waitForFunction(() => !window.commandRace.snapshot().busy && !window.commandRace.snapshot().unknown);
      process.stdout.write(JSON.stringify({ check: scenario, status: 'PASS', calls: result.calls.length, builders: result.builders }) + '\n');
    } catch (error) { failures.push(`${scenario}: ${error.message}; ${JSON.stringify(result)}`); }
    await page.close();
  }
  assert.deepEqual(failures, []);
} finally {
  if (browser) await browser.close();
  vite.kill('SIGTERM');
  await new Promise((resolve) => { if (vite.exitCode !== null) resolve(); else vite.once('exit', resolve); });
}
