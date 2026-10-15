import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createServer } from 'vite'
import { chromium } from 'playwright-core'
import net from 'node:net'
import path from 'node:path'
const config=JSON.parse(await readFile(process.argv[2],'utf8'))
const expectedMessages=config.expected_messages??1, expectedBytes=config.expected_bytes??320
assert.ok(Number.isSafeInteger(expectedMessages)&&expectedMessages>=1&&expectedMessages<=20)
assert.ok(Number.isSafeInteger(expectedBytes)&&expectedBytes>0)
assert.equal(new URL(config.foundation).hostname,'127.0.0.1')
process.env.VITE_USE_MOCK='false'
const port=await new Promise(resolve=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>resolve(p))})})
const server=await createServer({server:{host:'127.0.0.1',port,strictPort:true,proxy:{'/api':{target:config.foundation,changeOrigin:true}}}})
let browser
try{
  await server.listen();browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  const page=await browser.newPage({viewport:{width:1550,height:1100},locale:'zh-CN'}), errors=[];page.setDefaultTimeout(15000);page.on('pageerror',e=>errors.push(e.message))
  await page.goto(`http://127.0.0.1:${port}/login`,{waitUntil:'networkidle'})
  await page.getByRole('textbox',{name:'登录账号',exact:true}).fill(process.env.KAFKA_LIVE_WORKSPACE_USER);await page.getByRole('textbox',{name:'登录密码',exact:true}).fill(process.env.KAFKA_LIVE_WORKSPACE_PASSWORD)
  await page.getByRole('button',{name:'进入平台',exact:true}).click();await page.waitForURL('**/dashboard')
  await page.goto(`http://127.0.0.1:${port}/kafka-subscriptions?keyword=${config.grant_id}`,{waitUntil:'networkidle'})
  await page.getByTestId('subscription-table').getByRole('button',{name:'查看',exact:true}).click()
  const panel=page.getByTestId('consumption-panel');await panel.getByTestId('consumption-summary').waitFor()
  const summary=panel.getByTestId('consumption-summary')
  await summary.getByText(String(expectedBytes),{exact:true}).waitFor()
  const actual=(await (await page.request.get(`http://127.0.0.1:${port}/api/v1/kafka-subscriptions/${config.grant_id}/consumption`)).json()).data
  assert.equal(actual.summary.records,expectedMessages);assert.equal(actual.summary.bytes,expectedBytes);assert.ok(actual.summary.reports>=1)
  await page.route('**/consumption?**',route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:10103,message:'消费摘要读取测试故障',data:null})}),{times:1})
  await panel.getByRole('button',{name:'刷新消费摘要',exact:true}).click();await panel.getByTestId('consumption-error').waitFor();assert.equal(await panel.getByTestId('consumption-summary').count(),0)
  await panel.getByRole('button',{name:'重试消费摘要',exact:true}).click();await summary.getByText(String(expectedBytes),{exact:true}).waitFor()
  let release, started
  const delayed=new Promise(resolve=>{release=resolve}), entered=new Promise(resolve=>{started=resolve})
  await page.route('**/consumption?**',async route=>{started();await delayed;await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:10103,message:'旧请求错误不应覆盖新结果',data:null})})},{times:1})
  await panel.getByRole('button',{name:'刷新消费摘要',exact:true}).click();await entered
  await panel.getByRole('button',{name:'刷新消费摘要',exact:true}).dispatchEvent('click');await summary.getByText(String(expectedBytes),{exact:true}).waitFor()
  release();await page.waitForTimeout(300);assert.equal(await panel.getByTestId('consumption-error').count(),0)
  await page.screenshot({path:path.join(config.evidence,'consumption-real.png'),fullPage:true,animations:'disabled'});assert.deepEqual(errors,[])
  console.log(`真实需求方浏览器消费摘要通过：实际外部订阅上报${expectedMessages}条${expectedBytes}字节、失败清空、真实重试及旧响应隔离。`)
}finally{await browser?.close();await server.close()}
