import assert from 'node:assert/strict'
import { createServer as createTcpServer } from 'node:net'
import { createServer } from 'vite'
import { chromium } from 'playwright-core'

const port = await new Promise(resolve => {
  const socket = createTcpServer()
  socket.listen(0, '127.0.0.1', () => { const port = socket.address().port; socket.close(() => resolve(port)) })
})
const server = await createServer({ cacheDir:'../../.runtime/subscription-demo-vite-cache',
  define:{ 'import.meta.env.VITE_USE_MOCK':'"true"' }, server:{ host:'127.0.0.1', port, strictPort:true } })
let browser
try {
  await server.listen()
  browser = await chromium.launch({ executablePath:process.env.FOUNDATION_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless:true })
  const page = await browser.newPage(), requests = [], errors = []
  page.on('pageerror', error => errors.push(error.message))
  // Fail closed if a regression tries to contact any real backend during explicit demo.
  await page.route('**/api/v1/**', route => { requests.push(new URL(route.request().url()).pathname); return route.abort('failed') })
  const base = `http://127.0.0.1:${port}`
  await page.goto(base+'/login', {waitUntil:'networkidle'})
  await page.getByRole('button', {name:'进入平台',exact:true}).click()
  await page.waitForURL('**/dashboard')
  await page.goto(base+'/kafka-subscriptions?resource=RES_000001&capability=CAP_000002', {waitUntil:'networkidle'})
  await page.getByText('订阅审批仅支持真实接口，请关闭演示模式后使用。', {exact:true}).waitFor()
  assert.equal(await page.getByRole('button', {name:'新增订阅申请',exact:true}).isDisabled(), true)
  assert.equal(await page.getByRole('button', {name:'刷新',exact:true}).isDisabled(), true)
  assert.equal(await page.getByRole('dialog').count(), 0)
  assert.equal(await page.getByTestId('subscription-table').locator('tbody tr').count(), 0)
  assert.deepEqual(requests, [])
  assert.deepEqual(errors, [])
  console.log('Kafka subscription demo isolation passed: no real API, application, permission or credential operation.')
} finally { await browser?.close(); await server.close() }
