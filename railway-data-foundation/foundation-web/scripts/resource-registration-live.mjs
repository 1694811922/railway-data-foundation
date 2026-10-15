import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
import { chromium } from 'playwright-core'

process.env.VITE_USE_MOCK='false'
const response=await fetch('http://127.0.0.1:18080/api/v1/resources?limit=200'),real=await response.json()
assert.ok(response.ok && real.code===0,'Start Foundation before this test')
const server=await createServer({server:{host:'127.0.0.1',port:5185,strictPort:true}})
let browser,page
const releases=[]
function gate(){let release;const promise=new Promise(resolve=>{release=resolve}),timer=setTimeout(release,10000);const done=()=>{clearTimeout(timer);release()};releases.push(done);return {promise,release:done}}
try {
  await server.listen()
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  page=await browser.newPage({viewport:{width:1660,height:1100},locale:'zh-CN'})
  const errors=[];page.on('pageerror',error=>errors.push(error.message))
  await mkdir('.runtime',{recursive:true})
  async function settled(){await page.waitForFunction(()=>!document.querySelector('.el-loading-mask')&&!document.querySelector('.el-button.is-loading'))}
  async function screenshot(name){if(await page.locator('.el-message').count())await page.locator('.el-message').last().waitFor({state:'hidden'});await page.screenshot({path:'.runtime/'+name+'.png',fullPage:true,animations:'disabled'})}
  await page.goto('http://127.0.0.1:5185/',{waitUntil:'networkidle'});await page.getByRole('button',{name:'进入平台',exact:true}).click()
  await page.goto('http://127.0.0.1:5185/resources',{waitUntil:'networkidle'});await settled()
  const sourceA='SRC_'+'a'.repeat(32),sourceB='SRC_'+'b'.repeat(32),taskA='SCAN_'+'a'.repeat(32),taskB='SCAN_'+'b'.repeat(32),resourceId='RES_'+'a'.repeat(32)
  const registered={resource_id:resourceId,resource_name:'已登记作业表',source_id:sourceA,source_name:'真实核对来源A',scan_task_id:taskA,schema_name:'port_db',physical_table:'registered_table',resource_type:'TABLE',business_description:'已登记资源',owner_department:'研究组',security_level:'内部',update_mode:'实时',status:'DRAFT',version:1}
  const records=[registered],postBodies=[]
  let fault='',tablesGate=null,writeGate=null,failWrite=false,writeStarted=0,writeDone=0,catalogReads=0
  await page.route('**/api/v1/**',async route=>{
    const request=route.request(),url=new URL(request.url()),parts=url.pathname.split('/'),key=parts[3],id=parts[4],action=parts[5]
    const fulfill=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({code:status===200?0:10001,message:status===200?'success':'测试读取故障',data})})
    if(request.method()!=='GET'){
      assert.equal(key,'resources');writeStarted++;const wait=writeGate,fail=failWrite;writeGate=null
      const body=request.postDataJSON();postBodies.push(body);if(wait)await wait.promise
      writeDone++;if(fail)return route.abort('failed')
      if(action==='publish'){registered.status='PUBLISHED';registered.version++;return fulfill(registered)}
      if(id){assert.equal(id,resourceId);Object.assign(registered,body);registered.version++;return fulfill(registered)}
      return fulfill({...body,resource_id:'RES_'+'c'.repeat(32),source_name:'真实核对来源A',version:1,status:'DRAFT'})
    }
    if(key==='scan-tasks'){
      if(![taskA,taskB].includes(id))return fulfill(null,404)
      const second=id===taskB
      if(action==='tables'){
        const wait=tablesGate;tablesGate=null;if(wait)await wait.promise
        if(fault==='tables')return fulfill(null,503)
        return fulfill(second?[{schema_name:'rail_db',table_name:'train_dispatch',table_comment:'铁路调度结构',field_count:3,estimated_rows:12}]:
          [{schema_name:'port_db',table_name:'container_event',table_comment:'集装箱作业事件结构',field_count:12,estimated_rows:20},
            {schema_name:'port_db',table_name:'registered_table',table_comment:'已登记作业结构',field_count:4,estimated_rows:10}])
      }
      if(fault==='task')return fulfill(null,503)
      return fulfill({task_id:id,source_id:second?sourceB:sourceA,source_name:second?'真实核对来源B':'真实核对来源A',status:fault==='failed'?'FAILED':'SUCCESS'})
    }
    if(key!=='resources')return route.continue()
    if(id)return fulfill(action==='fields'||action==='capabilities'?[]:records.find(row=>row.resource_id===id))
    catalogReads++;const offset=Number(url.searchParams.get('offset')),limit=Number(url.searchParams.get('limit'))
    return fulfill(structuredClone(records.slice(offset,offset+limit)))
  })
  const entry=(changes={})=>({task:taskA,source:sourceA,schema:'port_db',fromTable:'container_event',sourceName:'不应使用的网址名称',...changes})
  async function goEntry(query){
    await page.evaluate(query=>{history.pushState({},'', '/resources?'+new URLSearchParams(query));dispatchEvent(new PopStateEvent('popstate'))},query)
    await page.getByRole('dialog').waitFor({state:'visible'})
  }
  const dialog=()=>page.getByRole('dialog'),save=()=>dialog().getByRole('button',{name:'保存资源',exact:true})
  const origin=()=>page.getByTestId('registration-origin')
  const table=()=>page.getByTestId('catalog-table'),recordRow=()=>table().getByRole('row').filter({hasText:registered.resource_name})
  await page.getByRole('button',{name:'刷新目录',exact:true}).click();await settled()
  for(const invalid of [entry({source:sourceB}),entry({fromTable:'not_scanned'}),entry({task:''})]){
    await goEntry(invalid);await page.getByTestId('registration-origin-error').waitFor()
    assert.equal(await save().isDisabled(),true);await save().evaluate(button=>button.dispatchEvent(new MouseEvent('click',{bubbles:true})))
    assert.equal(writeStarted,0)
  }
  fault='tables';await goEntry(entry());await page.getByTestId('registration-origin-error').waitFor()
  fault='';await dialog().getByRole('button',{name:'重新核对扫描来源',exact:true}).click();await origin().waitFor()
  assert.equal(await dialog().getByLabel('来源系统',{exact:true}).inputValue(),'真实核对来源A')
  assert.match(await origin().textContent(),/字段数：12/)
  await screenshot('resource-registration-origin')
  // Existing resources cannot be silently duplicated; the actual ID is used for navigation.
  await goEntry(entry({fromTable:'registered_table'}));await page.getByTestId('registration-duplicate').waitFor()
  assert.equal(await save().isDisabled(),true)
  await dialog().getByRole('button',{name:'查看已有资源',exact:true}).click();await page.waitForURL('**/resources/'+resourceId)
  await page.getByRole('heading',{name:'已登记作业表',exact:true}).waitFor()
  await page.getByRole('button',{name:/返回资源目录$/}).click();await settled()
  // Late scan origin cannot fill a newly selected source's form.
  const oldTables=gate();tablesGate=oldTables;await goEntry(entry());await page.getByTestId('registration-loading').waitFor()
  await goEntry(entry({task:taskB,source:sourceB,schema:'rail_db',fromTable:'train_dispatch'}));await origin().waitFor()
  oldTables.release();await page.waitForTimeout(100)
  assert.equal(await dialog().getByLabel('来源系统',{exact:true}).inputValue(),'真实核对来源B')
  assert.equal(await dialog().getByLabel('物理表',{exact:true}).inputValue(),'train_dispatch')
  await goEntry(entry());await origin().waitFor()
  await dialog().getByLabel('责任单位',{exact:true}).fill('资源登记验收组');await dialog().getByLabel('业务描述',{exact:true}).fill('人工确认后的业务描述')
  const storage=await page.evaluate(()=>JSON.stringify(Object.entries(localStorage).sort()))
  failWrite=true;const failGate=gate();writeGate=failGate;await save().click()
  await page.waitForFunction(()=>document.querySelector('[role="dialog"] .el-button.is-loading'))
  assert.equal(await dialog().getByLabel('资源名称',{exact:true}).isDisabled(),true)
  assert.equal(await dialog().getByRole('button',{name:'取消',exact:true}).isDisabled(),true)
  await save().evaluate(button=>button.dispatchEvent(new MouseEvent('click',{bubbles:true})));failGate.release()
  await page.getByText(/保存资源请求失败/).waitFor();assert.equal(writeStarted,1)
  assert.equal(await dialog().getByLabel('责任单位',{exact:true}).inputValue(),'资源登记验收组')
  for(const key of ['source_name','task_id','version','status','password'])assert.equal(key in postBodies[0],false)
  assert.equal(postBodies[0].scan_task_id,taskA)
  assert.equal(await page.evaluate(()=>JSON.stringify(Object.entries(localStorage).sort())),storage)
  // A late successful create must not reopen/navigate after the user leaves.
  failWrite=false;const oldSave=gate();writeGate=oldSave;await save().click()
  await page.waitForFunction(()=>document.querySelector('[role="dialog"] .el-button.is-loading'))
  const expectedDone=writeStarted
  await page.getByRole('menuitem',{name:'系统说明',exact:true}).click();await page.getByRole('heading',{name:'系统说明',exact:true}).waitFor()
  oldSave.release();const deadline=Date.now()+10000
  while(writeDone<expectedDone){assert.ok(Date.now()<deadline);await new Promise(resolve=>setTimeout(resolve,20))}
  await page.waitForTimeout(100);assert.equal(new URL(page.url()).pathname,'/about')
  await page.getByRole('menuitem',{name:'资源目录',exact:true}).click();await settled()
  await recordRow().getByRole('button',{name:/更多操作/}).click();await page.getByRole('menuitem',{name:'编辑资源',exact:true}).click()
  await dialog().getByLabel('责任单位',{exact:true}).fill('人工修订责任单位')
  const editResponse=page.waitForResponse(response=>response.request().method()==='PUT'&&response.url().endsWith('/resources/'+resourceId))
  await save().click();await editResponse;await page.getByRole('heading',{name:'已登记作业表',exact:true}).waitFor()
  assert.equal(postBodies.at(-1).version,1)
  assert.equal(registered.owner_department,'人工修订责任单位');assert.equal(registered.version,2)
  await page.getByRole('button',{name:/返回资源目录$/}).click();await settled()
  const oldPublish=gate();writeGate=oldPublish;await recordRow().getByRole('button',{name:'发布',exact:true}).click()
  await page.waitForFunction(()=>document.querySelector('.el-button.is-loading'))
  // Change the creation context on the same mounted catalog while publication is pending.
  await page.evaluate(query=>{history.pushState({},'', '/resources?'+new URLSearchParams(query));dispatchEvent(new PopStateEvent('popstate'))},entry({task:taskB,source:sourceB,schema:'rail_db',fromTable:'train_dispatch'}))
  const reads=catalogReads;oldPublish.release();await settled()
  assert.equal(catalogReads,reads,'Old publish refreshed the new creation context')
  assert.equal(await dialog().count(),0);assert.equal(registered.status,'PUBLISHED')
  await page.getByRole('button',{name:'继续登记所选表',exact:true}).click();await origin().waitFor()
  assert.equal(await dialog().getByLabel('物理表',{exact:true}).inputValue(),'train_dispatch')
  await dialog().getByRole('button',{name:'取消',exact:true}).click()
  await page.getByRole('button',{name:'刷新目录',exact:true}).click();await settled();await screenshot('resource-registration-catalog')
  assert.deepEqual(errors,[])
  console.log('Resource registration passed: real read; task/source/table verification; forged name ignored; malformed/foreign origin blocked; retry and duplicate navigation; late origin/create/publish isolated; form locks/payload; real failure preserves input and storage; edit persists. Fixture writes intercepted.')
}catch(error){await page?.screenshot({path:'.runtime/resource-registration-error.png',fullPage:true}).catch(()=>{});throw error}
finally{for(const release of releases)release();await browser?.close();await server.close()}
