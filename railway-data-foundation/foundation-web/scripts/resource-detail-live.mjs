import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
import { chromium } from 'playwright-core'

process.env.VITE_USE_MOCK='false'
const api='http://127.0.0.1:18080/api/v1'
const response=await fetch(api+'/resources?limit=1')
assert.ok(response.ok,'Start Foundation before this test')
const json=await response.json();assert.equal(json.code,0)
const realId=json.data[0]?.resource_id
const server=await createServer({server:{host:'127.0.0.1',port:5183,strictPort:true}})
let browser,page
const gates=[]
function gate() {
  let release
  const promise=new Promise(resolve=>{release=resolve})
  const timer=setTimeout(release,10000)
  gates.push(()=>{clearTimeout(timer);release()})
  return {promise,release:()=>{clearTimeout(timer);release()}}
}
try {
  await server.listen()
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  page=await browser.newPage({viewport:{width:1660,height:1100},locale:'zh-CN'})
  const errors=[];page.on('pageerror',error=>errors.push(error.message))
  await mkdir('.runtime',{recursive:true})
  async function screenshot(name) {
    if(await page.locator('.el-message').count())await page.locator('.el-message').last().waitFor({state:'hidden'})
    await page.screenshot({path:'.runtime/'+name+'.png',fullPage:true,animations:'disabled'})
  }
  async function settle(){await page.waitForFunction(()=>!document.querySelector('.el-loading-mask')&&!document.querySelector('.el-button.is-loading'))}
  async function tab(name){await page.getByRole('tab',{name:new RegExp(name)}).click()}
  const fieldTable=()=>page.getByTestId('resource-field-list'),capTable=()=>page.getByTestId('resource-capability-list')
  await page.goto('http://127.0.0.1:5183/',{waitUntil:'networkidle'});await page.getByRole('button',{name:'进入平台',exact:true}).click()
  if(realId){
    const fieldsResponse=await fetch(api+'/resources/'+realId+'/fields'),fieldsBody=await fieldsResponse.json()
    assert.ok(fieldsResponse.ok);assert.equal(fieldsBody.code,0)
    await page.goto('http://127.0.0.1:5183/resources/'+realId,{waitUntil:'networkidle'});await settle();await tab('字段元数据')
    assert.match(await page.getByTestId('resource-field-count').textContent(),new RegExp('已读取 '+fieldsBody.data.length+' 项'))
    await screenshot('resource-detail-real')
  }
  const idA='RES_'+ 'a'.repeat(32),idB='RES_'+'b'.repeat(32)
  const resources=Object.fromEntries([idA,idB].map((id,i)=>[id,{resource_id:id,resource_name:i?'第二资源':'工作台验收资源',source_id:'SRC_FIXTURE',source_name:'测试来源',
    schema_name:'port_source_db',physical_table:'container_event',resource_type:'TABLE',business_description:'仅用于浏览器专项的元数据夹具',owner_department:'测试组',security_level:'内部',update_mode:'实时',status:'DRAFT',version:1}]))
  const makeFields=(id,n)=>Array.from({length:n},(_,i)=>({resource_id:id,field_name:'field_'+i.toString().padStart(3,'0'),business_name:(id===idB?'第二资源字段':'作业字段')+i,
    data_type:i%2?'datetime':'bigint',business_description:'业务说明 '+i,technical_comment:'扫描注释 '+i,primary_key:i===0,time_field:i%2===1,sensitivity_level:i%2?'内部':'敏感'}))
  const fieldsById={[idA]:makeFields(idA,155),[idB]:makeFields(idB,3)}
  const caps=Array.from({length:61},(_,i)=>({resource_id:idA,capability_id:'CAP_'+i.toString(16).padStart(32,'0'),capability_name:'事件能力 '+i,
    type:['REST_API','KAFKA_TOPIC','CDC_STREAM','READONLY_QUERY'][i%4],status:i%2?'ENABLED':'DRAFT',health_status:i%2?'UP':'UNKNOWN',endpoint:i%4===0?'/containers/events':null,topic:i%4===1?'port.container.event.'+i:null,version:1}))
  let fault='',holdField=null,holdCap=null,holdBase=null,holdWrite=null,writeFail=false,writeCount=0,lateLabel=''
  const reads={fields:0,capabilities:0,base:0},payloads=[]
  await page.route('**/api/v1/**',async route=>{
    const request=route.request(),parts=new URL(request.url()).pathname.split('/'),id=parts[4],section=parts[5]
    const fulfill=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({code:status===200?0:10001,message:status===200?'success':'测试读取故障',data})})
    if(request.method()!=='GET') {
      assert.equal(parts[3],'resources');assert.ok(id===idA || id===idB);assert.ok(section==='fields' || section==='publish')
      writeCount++;const fail=writeFail,wait=holdWrite;holdWrite=null
      const body=request.postDataJSON();payloads.push({id,section,body})
      if(wait)await wait.promise
      if(fail)return route.abort('failed')
      resources[id].version++
      if(section==='publish'){resources[id].status='PUBLISHED';return fulfill(resources[id])}
      const field=fieldsById[id].find(row=>row.field_name===parts[6]);Object.assign(field,body);return fulfill({...field,resource_version:resources[id].version})
    }
    if(parts[3]!=='resources' || ![idA,idB].includes(id))return route.continue()
    const failure=fault===section || (!section && fault==='base')
    if(section==='fields'){
      reads.fields++;const rows=structuredClone(fieldsById[id]).map(row=>({...row,resource_version:resources[id].version}));if(lateLabel && id===idA)rows[0].business_name=lateLabel
      const wait=holdField;holdField=null;if(wait)await wait.promise
      return fulfill(failure?null:rows,failure?503:200)
    }
    if(section==='capabilities'){
      reads.capabilities++;const wait=holdCap;holdCap=null;if(wait)await wait.promise
      return fulfill(failure?null:id===idA?caps:[],failure?503:200)
    }
    reads.base++;const row=structuredClone(resources[id]),wait=holdBase;holdBase=null;if(wait)await wait.promise
    return fulfill(failure?null:row,failure?503:200)
  })
  async function go(id){
    await page.evaluate(value=>{history.pushState({},'', '/resources/'+value);dispatchEvent(new PopStateEvent('popstate'))},id)
    await page.getByRole('heading',{name:resources[id].resource_name,exact:true}).waitFor()
  }
  async function select(label,value){const control=page.getByRole('combobox',{name:label,exact:true});await control.focus();await control.press('ArrowDown');await page.getByRole('option',{name:value,exact:true}).click()}
  // The slow capability section must not hide successful fields or block editing them.
  const capGate=gate();holdCap=capGate;await go(idA);await tab('字段元数据')
  await page.getByTestId('resource-field-count').waitFor();assert.match(await page.getByTestId('resource-field-count').textContent(),/已读取 155 项/)
  assert.equal(await fieldTable().getByRole('row').count(),11)
  await fieldTable().getByRole('button',{name:'编辑',exact:true}).first().click();await page.getByRole('dialog').getByRole('button',{name:'取消',exact:true}).click()
  capGate.release();await settle()
  await page.getByTestId('resource-field-pagination').getByRole('button',{name:'下一页'}).click()
  assert.match(await fieldTable().textContent(),/field_010/)
  await page.getByLabel('资源字段关键词',{exact:true}).fill('FIELD_154 扫描');assert.equal(await fieldTable().getByRole('row').count(),2)
  await screenshot('resource-detail-fields-search')
  await page.getByRole('button',{name:'重置字段筛选',exact:true}).click()
  await select('字段标记','时间字段');await select('字段敏感等级','内部')
  assert.match(await page.getByTestId('resource-field-count').textContent(),/匹配 77 项/)
  await screenshot('resource-detail-fields')
  await page.getByRole('button',{name:'重置字段筛选',exact:true}).click()
  // Independent retry does not reread unaffected basic information or capabilities.
  fault='fields';const capReads=reads.capabilities,baseReads=reads.base
  await page.getByRole('button',{name:'重新加载字段',exact:true}).click();await page.getByTestId('resource-field-error').waitFor();assert.equal(await fieldTable().count(),0)
  assert.equal(reads.capabilities,capReads);assert.equal(reads.base,baseReads)
  fault='';await page.getByRole('button',{name:'重新加载字段',exact:true}).click();await settle()
  await tab('接口能力');assert.match(await page.getByTestId('resource-capability-count').textContent(),/已读取 61 项/)
  assert.equal(await capTable().getByRole('row').count(),11)
  await select('关联能力类型','Kafka主题');await select('关联能力状态','已启用');await select('关联能力健康状态','正常')
  await page.getByLabel('关联能力关键词',{exact:true}).fill('event.57');assert.equal(await capTable().getByRole('row').count(),2)
  assert.equal(await page.getByRole('button',{name:/注册能力$/}).isDisabled(),true)
  await screenshot('resource-detail-capabilities')
  fault='capabilities';const fieldReads=reads.fields
  await page.getByRole('button',{name:'重新加载能力',exact:true}).click();await page.getByTestId('resource-capability-error').waitFor()
  assert.equal(reads.fields,fieldReads);await screenshot('resource-detail-capability-failure')
  fault='';await page.getByRole('button',{name:'重新加载能力',exact:true}).click();await settle()
  // Late field and basic-info reads cannot overwrite the same mounted view after changing resource.
  await tab('字段元数据');lateLabel='旧资源响应';const fieldGate=gate();holdField=fieldGate
  await page.getByRole('button',{name:'重新加载字段',exact:true}).click();await go(idB);await settle();await tab('字段元数据')
  fieldGate.release();await page.waitForTimeout(100)
  assert.match(await fieldTable().textContent(),/第二资源字段/);assert.equal((await fieldTable().textContent()).includes('旧资源响应'),false)
  lateLabel='';const baseGate=gate();holdBase=baseGate;await page.getByRole('button',{name:'刷新详情',exact:true}).click()
  await page.waitForFunction(()=>!document.querySelector('.detail-hero'));await go(idA);await settle();baseGate.release();await page.waitForTimeout(100)
  assert.equal(await page.getByRole('heading',{name:'第二资源',exact:true}).count(),0)
  await tab('字段元数据');const storage=await page.evaluate(()=>JSON.stringify(Object.entries(localStorage).sort()))
  await fieldTable().getByRole('button',{name:'编辑',exact:true}).first().click()
  const dialog=page.getByRole('dialog');await dialog.getByLabel('业务名称',{exact:true}).fill('人工确认字段')
  const writeGate=gate();holdWrite=writeGate;writeFail=true
  const savedButton=dialog.getByRole('button',{name:'保存字段',exact:true});await savedButton.click()
  await page.waitForFunction(()=>document.querySelector('[role="dialog"] .el-button.is-loading'))
  await savedButton.evaluate(button=>button.dispatchEvent(new MouseEvent('click',{bubbles:true})))
  assert.equal(await dialog.getByLabel('业务名称',{exact:true}).isDisabled(),true);assert.equal(await dialog.getByRole('button',{name:'取消',exact:true}).isDisabled(),true)
  writeGate.release();await page.getByText(/保存字段请求失败/).waitFor();assert.equal(writeCount,1)
  assert.equal(await dialog.getByLabel('业务名称',{exact:true}).inputValue(),'人工确认字段');assert.equal(resources[idA].version,1)
  assert.deepEqual(Object.keys(payloads[0].body).sort(),['business_description','business_name','resource_version','sensitivity_level','time_field'])
  assert.equal(payloads[0].body.resource_version,1)
  assert.equal(await page.evaluate(()=>JSON.stringify(Object.entries(localStorage).sort())),storage)
  writeFail=false;await savedButton.click();await page.getByText('人工确认字段',{exact:true}).waitFor();assert.equal(resources[idA].version,2)
  // A successful old write must not close/refresh/navigate the newly selected resource.
  await fieldTable().getByRole('button',{name:'编辑',exact:true}).first().click();const oldWrite=gate();holdWrite=oldWrite
  await dialog.getByLabel('业务名称',{exact:true}).fill('旧页面提交');await savedButton.click()
  await page.waitForFunction(()=>document.querySelector('[role="dialog"] .el-button.is-loading'))
  await go(idB);await tab('字段元数据');await page.getByTestId('resource-field-count').waitFor()
  assert.equal(await fieldTable().getByRole('button',{name:'编辑',exact:true}).first().isDisabled(),true)
  oldWrite.release();await settle();assert.equal(await page.getByRole('heading',{name:'第二资源',exact:true}).count(),1)
  assert.equal(await dialog.count(),0);assert.equal(await fieldTable().getByRole('button',{name:'编辑',exact:true}).first().isEnabled(),true)
  await go(idA);await settle();const publishGate=gate();holdWrite=publishGate
  await page.getByRole('button',{name:'发布资源',exact:true}).click();await go(idB);publishGate.release();await settle()
  assert.equal(resources[idA].status,'PUBLISHED');assert.equal(resources[idB].status,'DRAFT')
  assert.equal(await page.getByRole('heading',{name:'第二资源',exact:true}).count(),1)
  // Basic-info failures clear the whole resource; retries use the requested ID.
  fault='base';await page.getByRole('button',{name:'刷新详情',exact:true}).click();await page.getByRole('button',{name:'重新加载',exact:true}).waitFor()
  assert.equal(await page.locator('.detail-hero').count(),0)
  fault='';await page.getByRole('button',{name:'重新加载',exact:true}).click();await settle()
  await go(idA);await settle();await tab('接口能力');await page.getByRole('button',{name:'管理关联能力',exact:true}).click();await page.waitForURL('**/capabilities?resource='+idA)
  assert.deepEqual(errors,[])
  console.log('Resource detail passed: real fields; 155-field/61-capability search and paging; independent reads/retry; late reads/writes isolated; field payload and duplicate protection; failure preserves input; publish scoped; capability navigation. All fixture writes intercepted.')
} catch(error) {await page?.screenshot({path:'.runtime/resource-detail-error.png',fullPage:true}).catch(()=>{});throw error}
finally {for(const release of gates)release();await browser?.close();await server.close()}
