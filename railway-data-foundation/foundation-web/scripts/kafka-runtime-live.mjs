import assert from 'node:assert/strict'
import {createServer} from 'vite'
import {chromium} from 'playwright-core'
import {mkdir} from 'node:fs/promises'

// Real API rejection/cleanup, then intercepted UI lifecycle checks. Broker success is tested in Java.
process.env.VITE_USE_MOCK='false'
const api='http://127.0.0.1:18080/api/v1'
async function data(response){assert.ok(response.ok(),'HTTP '+response.status());const body=await response.json();assert.equal(body.code,0);return body.data}
const server=await createServer({server:{host:'127.0.0.1',port:5191,strictPort:true}})
let browser,capability,release
try {
  await server.listen();await mkdir('.runtime',{recursive:true})
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  const page=await browser.newPage({viewport:{width:1660,height:1100},locale:'zh-CN'});page.setDefaultTimeout(12000)
  const resources=await data(await page.request.get(api+'/resources?limit=200'))
  const resource=resources.find(row=>row.status==='PUBLISHED'&&row.physical_table==='container_event')
  assert.ok(resource,'Run the real REST browser fixture first')
  const before=await data(await page.request.get(api+'/resources/'+resource.resource_id))
  const fields=await data(await page.request.get(api+'/resources/'+resource.resource_id+'/fields'))
  const name='UI_KAFKA_RUNTIME_'+crypto.randomUUID()
  capability=await data(await page.request.post(api+'/capabilities',{data:{capability_name:name,resource_id:resource.resource_id,type:'KAFKA_TOPIC',protocol:'KAFKA',auth_type:'SASL',topic:'ui-isolated-'+crypto.randomUUID()}}))
  await page.goto('http://127.0.0.1:5191/',{waitUntil:'networkidle'});await page.getByRole('button',{name:'进入平台',exact:true}).click();await page.goto('http://127.0.0.1:5191/capabilities',{waitUntil:'networkidle'})
  await page.getByRole('heading',{name:'接口能力管理',exact:true}).waitFor()
  await page.getByPlaceholder('搜索能力名称、编号、资源或访问地址').fill(name)
  const row=page.getByTestId('capability-table').getByRole('row').filter({hasText:name})
  await row.waitFor();assert.equal(await row.getByRole('button',{name:'启用',exact:true}).isDisabled(),true)
  const failed=page.waitForResponse(r=>r.url().endsWith('/'+capability.capability_id+'/verify'))
  await row.getByRole('button',{name:'验证',exact:true}).click();assert.equal((await failed).status(),400)
  await row.getByText('草稿',{exact:true}).waitFor();assert.equal(await row.getByRole('button',{name:'启用',exact:true}).isDisabled(),true)
  await row.getByRole('button',{name:'查看',exact:true}).click()
  const dialog=page.getByRole('dialog');await dialog.getByText('消息发送状态',{exact:true}).waitFor()
  await dialog.getByText(/订阅授权尚未接入/).waitFor()
  const runtime=await data(await page.request.get(api+'/capabilities/'+capability.capability_id+'/kafka-runtime'))
  assert.equal(runtime.enabled,false);assert.equal(runtime.type,'KAFKA_TOPIC')
  await page.route('**/capabilities/'+capability.capability_id+'/kafka-runtime',route=>route.abort('failed'))
  await dialog.getByRole('button',{name:'刷新发送状态'}).click();await dialog.getByText(/发送状态加载失败/).waitFor()
  assert.equal(await dialog.locator('.el-alert--success').count(),0)
  await page.unroute('**/capabilities/'+capability.capability_id+'/kafka-runtime')
  await dialog.getByRole('button',{name:'刷新发送状态'}).click();await dialog.getByText(/发送状态加载失败/).waitFor({state:'hidden'})
  await dialog.locator('.el-dialog__headerbtn').click()

  // UI-only successful lifecycle: no real grant or production state is changed.
  const state={...capability};let writeFail=false
  await page.route('**/api/v1/capabilities**',async route=>{
    const req=route.request(),url=new URL(req.url()),suffix=url.pathname.split('/').at(-1)
    const fulfill=value=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:0,message:'success',data:value})})
    if(req.method()==='GET'&&url.pathname==='/api/v1/capabilities'){
      const actual=await route.fetch(),body=await actual.json();body.data=body.data.map(row=>row.capability_id===state.capability_id?state:row)
      return route.fulfill({response:actual,json:body})
    }
    if(!url.pathname.includes(state.capability_id))return route.continue()
    if(req.method()!=='GET') {
      if(writeFail)return route.abort('failed')
      state.status=suffix==='verify'?(state.status==='DISABLED'?'DISABLED':'VERIFIED'):suffix==='enable'?'ENABLED':'DISABLED'
      state.health_status='UP';state.version++;return fulfill(state)
    }
    if(suffix==='kafka-runtime')return fulfill({capability_id:state.capability_id,type:state.type,topic:state.topic,auth_type:state.auth_type,enabled:state.status==='ENABLED',reason:'界面测试发送状态'})
    return route.continue()
  })
  await row.getByRole('button',{name:'验证',exact:true}).click();await row.getByText('已验证',{exact:true}).waitFor()
  await row.getByRole('button',{name:'启用',exact:true}).click();await row.getByText('已启用',{exact:true}).waitFor()
  writeFail=true;await row.getByRole('button',{name:'停用',exact:true}).click();await page.getByText(/接口能力操作结果尚未确认/).waitFor()
  await row.getByText('已启用',{exact:true}).waitFor();assert.equal(state.status,'ENABLED')
  writeFail=false;await row.getByRole('button',{name:'停用',exact:true}).click();await row.getByText('已停用',{exact:true}).waitFor()
  await row.getByRole('button',{name:'验证',exact:true}).click();await row.getByText('已停用',{exact:true}).waitFor();assert.equal(state.status,'DISABLED')
  const pending=new Promise(resolve=>release=resolve)
  await page.route('**/capabilities/'+capability.capability_id+'/kafka-runtime',async route=>{await pending;await route.fulfill({json:{code:0,data:{capability_id:capability.capability_id,enabled:true,reason:'不应出现的旧响应'}}})})
  await row.getByRole('button',{name:'查看',exact:true}).click();await dialog.getByText('消息发送状态',{exact:true}).waitFor()
  await dialog.locator('.el-dialog__headerbtn').click();release();await page.waitForTimeout(150)
  assert.equal(await page.getByText('不应出现的旧响应').count(),0)
  assert.deepEqual(await data(await page.request.get(api+'/resources/'+resource.resource_id)),before)
  assert.deepEqual(await data(await page.request.get(api+'/resources/'+resource.resource_id+'/fields')),fields)
  console.log('Kafka browser passed: real API rejection/runtime/retry; intercepted lifecycle, failed writes, disabled revalidation, late detail response isolation; resource and fields unchanged. No subscriber grant created.')
} finally {
  release?.();await browser?.close();await server.close()
  if(capability){const res=await fetch(api+'/capabilities/'+capability.capability_id,{method:'DELETE'});assert.ok(res.ok,'Temporary Kafka draft cleanup failed')}
}
