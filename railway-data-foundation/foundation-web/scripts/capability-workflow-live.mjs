import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
import { chromium } from 'playwright-core'

process.env.VITE_USE_MOCK='false'
const realResponse=await fetch('http://127.0.0.1:18080/api/v1/capabilities?limit=200'),real=await realResponse.json()
assert.ok(realResponse.ok && real.code===0,'Start Foundation before this test')
const server=await createServer({server:{host:'127.0.0.1',port:5184,strictPort:true}})
let browser,page
const releases=[]
function gate(){let release;const promise=new Promise(resolve=>{release=resolve}),timer=setTimeout(release,10000);const done=()=>{clearTimeout(timer);release()};releases.push(done);return {promise,release:done}}
try {
  await server.listen()
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  page=await browser.newPage({viewport:{width:1660,height:1100},locale:'zh-CN'})
  const errors=[];page.on('pageerror',error=>errors.push(error.message))
  await mkdir('.runtime',{recursive:true})
  async function ready(){await page.getByRole('heading',{name:'接口能力管理',exact:true}).waitFor();await page.waitForFunction(()=>!document.querySelector('.el-button.is-loading')&&!document.querySelector('.el-loading-mask'))}
  async function screenshot(name){if(await page.locator('.el-message').count())await page.locator('.el-message').last().waitFor({state:'hidden'});await page.screenshot({path:'.runtime/'+name+'.png',fullPage:true,animations:'disabled'})}
  await page.goto('http://127.0.0.1:5184/',{waitUntil:'networkidle'});await page.getByRole('button',{name:'进入平台',exact:true}).click()
  await page.goto('http://127.0.0.1:5184/capabilities',{waitUntil:'networkidle'});await ready()
  await page.getByRole('button',{name:'＋ 注册接口能力',exact:true}).waitFor()
  const resA='RES_'+'a'.repeat(32),resB='RES_'+'b'.repeat(32),capId=i=>'CAP_'+String(i).padStart(32,'0')
  const resources=[resA,resB].map((id,i)=>({resource_id:id,resource_name:i?'第二资源':'测试资源',status:'PUBLISHED',physical_table:'container_event'}))
  const caps=[{capability_id:capId(1),capability_name:'工作流REST接口',resource_id:resA,type:'REST_API',http_method:'GET',endpoint:'/containers/events',protocol:'HTTP',auth_type:'NONE',status:'DRAFT',health_status:'UNKNOWN',version:1},
    {capability_id:capId(2),capability_name:'变化流能力',resource_id:resA,type:'CDC_STREAM',topic:'port.event.v1',protocol:'KAFKA',auth_type:'NONE',status:'VERIFIED',health_status:'UP',version:1},
    {capability_id:capId(3),capability_name:'第二资源REST',resource_id:resB,type:'REST_API',http_method:'GET',endpoint:'/containers/events',protocol:'HTTP',auth_type:'NONE',status:'DISABLED',health_status:'UP',version:1}]
  let writeGate=null,writeFail=false,guideBad=false,guideFail=false,writeStarted=0,writeDone=0,readCalls=0
  const payloads=[]
  await page.route('**/api/v1/**',async route=>{
    const request=route.request(),url=new URL(request.url()),parts=url.pathname.split('/'),key=parts[3],id=parts[4],action=parts[5]
    const fulfill=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({code:status===200?0:10001,message:status===200?'success':'测试读取故障',data})})
    if(request.method()!=='GET') {
      assert.equal(key,'capabilities');const record=caps.find(row=>row.capability_id===id)
      writeStarted++;const fail=writeFail,wait=writeGate;writeGate=null
      const body=request.postDataJSON();payloads.push(body)
      if(wait)await wait.promise
      if(fail){writeDone++;return route.abort('failed')}
      assert.ok(record,'Only fixture capabilities may be mutated')
      if(action==='verify'){record.status=record.status==='DISABLED'?'DISABLED':'VERIFIED';record.health_status='UP'}
      else if(action==='enable')record.status='ENABLED'
      else if(action==='disable')record.status='DISABLED'
      else {assert.equal(request.method(),'PUT');Object.assign(record,body)}
      record.version++;writeDone++;return fulfill(record)
    }
    if(!['capabilities','resources'].includes(key))return route.continue()
    if(action==='invocation') {
      if(guideFail)return fulfill(null,503)
      const record=caps.find(row=>row.capability_id===id)
      return fulfill({capability_id:guideBad?capId(99):id,http_method:'GET',provider_base_url:'http://127.0.0.1:18081/',endpoint:'/containers/events',
        request_url:'http://127.0.0.1:18081/containers/events',header_name:'X-Capability-Id',header_value:id,enabled:record.status==='ENABLED',reason:record.status==='ENABLED'?'测试已启用':'测试未启用'})
    }
    readCalls++;const offset=Number(url.searchParams.get('offset')),limit=Number(url.searchParams.get('limit'))
    return fulfill(structuredClone((key==='resources'?resources:caps).slice(offset,offset+limit)))
  })
  async function routeResource(id){await page.evaluate(value=>{history.pushState({},'', '/capabilities?resource='+value);dispatchEvent(new PopStateEvent('popstate'))},id);await ready()}
  const table=()=>page.getByTestId('capability-table'),row=name=>table().getByRole('row').filter({hasText:name})
  await page.getByRole('button',{name:'刷新列表',exact:true}).click();await ready()
  const kafka=row('变化流能力'),rest=row('工作流REST接口')
  assert.equal(await kafka.getByRole('button',{name:'启用',exact:true}).isDisabled(),true)
  assert.match(await kafka.getByRole('button',{name:'启用',exact:true}).locator('..').getAttribute('title'),/尚未实现/)
  await kafka.getByRole('button',{name:'启用',exact:true}).evaluate(button=>button.dispatchEvent(new MouseEvent('click',{bubbles:true})))
  assert.equal(writeStarted,0,'Unsupported action reached API')
  await rest.getByRole('button',{name:'编辑',exact:true}).click();const dialog=page.getByRole('dialog')
  assert.equal(await dialog.getByLabel('能力名称',{exact:true}).getAttribute('maxlength'),'120')
  assert.equal(await dialog.getByLabel('所属资源',{exact:true}).isDisabled(),true)
  assert.equal(await dialog.getByLabel('能力类型',{exact:true}).isDisabled(),true)
  await dialog.getByLabel('能力名称',{exact:true}).fill('人工编辑REST')
  const storage=await page.evaluate(()=>JSON.stringify(Object.entries(localStorage).sort()))
  writeFail=true;const failGate=gate();writeGate=failGate
  const save=dialog.getByRole('button',{name:'保存能力',exact:true});await save.click()
  await page.waitForFunction(()=>document.querySelector('[role="dialog"] .el-button.is-loading'))
  assert.equal(await dialog.getByLabel('能力名称',{exact:true}).isDisabled(),true)
  assert.equal(await dialog.getByRole('button',{name:'填入本机示例配置',exact:true}).isDisabled(),true)
  await save.evaluate(button=>button.dispatchEvent(new MouseEvent('click',{bubbles:true})))
  failGate.release();await page.getByText(/接口能力操作结果尚未确认/).waitFor();assert.equal(writeStarted,1)
  assert.equal(await dialog.getByLabel('能力名称',{exact:true}).inputValue(),'人工编辑REST')
  for(const key of ['version','status','health_status','capability_id','password'])assert.equal(key in payloads[0],false)
  assert.equal(await page.evaluate(()=>JSON.stringify(Object.entries(localStorage).sort())),storage)
  writeFail=false;const oldSave=gate();writeGate=oldSave;await save.click()
  await page.waitForFunction(()=>document.querySelector('[role="dialog"] .el-button.is-loading'))
  await routeResource(resB);assert.equal(await dialog.count(),0);const callsAfterSwitch=readCalls
  oldSave.release();await page.waitForFunction(()=>!document.querySelector('.el-button.is-loading'));await page.waitForTimeout(100)
  assert.equal(readCalls,callsAfterSwitch,'Old save refreshed new context')
  assert.equal(await row('第二资源REST').count(),1);assert.equal(await row('人工编辑REST').count(),0)
  await routeResource(resA);await row('人工编辑REST').getByRole('button',{name:'验证',exact:true}).click();await ready()
  await row('人工编辑REST').getByRole('button',{name:'查看',exact:true}).click();await page.getByText('测试未启用',{exact:true}).waitFor()
  await dialog.getByText('已验证',{exact:true}).waitFor()
  // Refreshing the list while detail remains open must update both status and invocation snapshot.
  caps[0].status='ENABLED';caps[0].version++
  await page.getByRole('button',{name:'刷新列表',exact:true}).evaluate(button=>button.dispatchEvent(new MouseEvent('click',{bubbles:true})))
  await page.getByText('测试已启用',{exact:true}).waitFor();await dialog.getByText('已启用',{exact:true}).waitFor()
  guideBad=true;await dialog.getByRole('button',{name:'刷新调用信息',exact:true}).click();await page.getByText(/调用信息加载失败/).waitFor()
  assert.equal(await dialog.getByLabel('PowerShell 调用命令',{exact:true}).count(),0)
  guideBad=false;guideFail=true;await dialog.getByRole('button',{name:'刷新调用信息',exact:true}).click();await page.getByText(/调用信息加载失败/).waitFor()
  guideFail=false;await dialog.getByRole('button',{name:'刷新调用信息',exact:true}).click();await page.getByText('测试已启用',{exact:true}).waitFor()
  await screenshot('capability-workflow-detail');await dialog.locator('.el-dialog__headerbtn').click()
  const oldAction=gate();writeGate=oldAction;await row('人工编辑REST').getByRole('button',{name:'停用',exact:true}).click()
  await page.waitForFunction(()=>document.querySelector('.el-button.is-loading'));await routeResource(resB)
  const afterActionSwitch=readCalls;oldAction.release();await ready();assert.equal(readCalls,afterActionSwitch)
  assert.equal(caps[0].status,'DISABLED');assert.equal(await row('第二资源REST').count(),1)
  await row('第二资源REST').getByRole('button',{name:'验证',exact:true}).click();await ready()
  assert.equal(caps[2].status,'DISABLED','Verification implicitly enabled capability')
  await row('第二资源REST').getByRole('button',{name:'启用',exact:true}).click();await ready();assert.equal(caps[2].status,'ENABLED')
  await screenshot('capability-workflow-list')
  const unmountGate=gate();writeGate=unmountGate;await row('第二资源REST').getByRole('button',{name:'停用',exact:true}).click()
  await page.waitForFunction(()=>document.querySelector('.el-button.is-loading'));const expectedDone=writeStarted
  await page.getByRole('menuitem',{name:'系统说明',exact:true}).click();await page.getByRole('heading',{name:'系统说明',exact:true}).waitFor()
  unmountGate.release();const deadline=Date.now()+10000
  while(writeDone<expectedDone){assert.ok(Date.now()<deadline,'Pending fixture write did not complete');await new Promise(resolve=>setTimeout(resolve,20))}
  await page.waitForTimeout(100);assert.equal(new URL(page.url()).pathname,'/about')
  assert.equal(await page.getByText('停用完成',{exact:true}).count(),0)
  assert.equal(await page.getByTestId('about-capability-support').getByRole('row').count(),5)
  assert.equal(await page.getByText(/能力启用及后续治理模块尚未开放/).count(),0)
  await screenshot('about-workflow')
  assert.deepEqual(errors,[])
  console.log('Capability workflow passed: real read; unsupported action blocked; edit scope/input lock/payload; late save/action/unmount ignored; refreshed detail states; mismatched/failed invocation hidden; verification keeps disabled; explicit enable; accurate support page. Fixture writes intercepted.')
} catch(error){await page?.screenshot({path:'.runtime/capability-workflow-error.png',fullPage:true}).catch(()=>{});throw error}
finally {for(const release of releases)release();await browser?.close();await server.close()}
