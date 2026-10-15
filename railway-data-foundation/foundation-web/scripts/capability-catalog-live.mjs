import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { chromium } from 'playwright-core'
import { mkdir } from 'node:fs/promises'
import { loadCapabilityCatalog } from '../src/utils/capabilityCatalog.js'

process.env.VITE_USE_MOCK='false'
const api='http://127.0.0.1:18080/api/v1'
const real=await loadCapabilityCatalog(Object.fromEntries(['capabilities','resources'].map(key=>[key,async params=>{
  const response=await fetch(api+'/'+key+'?'+new URLSearchParams(params)),body=await response.json()
  assert.equal(response.ok,true);assert.equal(body.code,0);return body.data
}])))
assert.equal(real.capabilities.error || real.resources.error,false,'Start Foundation before this test')
const server=await createServer({server:{host:'127.0.0.1',port:5180,strictPort:true}})
let browser,page,releaseOld,releaseSave
try {
  await server.listen()
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  page=await browser.newPage({viewport:{width:1660,height:1100},locale:'zh-CN'})
  const errors=[];page.on('pageerror',error=>errors.push(error.message))
  async function ready(){await page.getByRole('heading',{name:'接口能力管理',exact:true}).waitFor();await page.waitForFunction(()=>!document.querySelector('.el-button.is-loading')&&!document.querySelector('.el-loading-mask'))}
  async function refresh(){await page.getByRole('button',{name:'刷新列表',exact:true}).click();await ready()}
  const table=()=>page.getByTestId('capability-table')
  const register=()=>page.getByRole('button',{name:'＋ 注册接口能力',exact:true})
  await page.goto('http://127.0.0.1:5180/',{waitUntil:'networkidle'})
  await page.getByRole('button',{name:'进入平台',exact:true}).click()
  await page.goto('http://127.0.0.1:5180/capabilities',{waitUntil:'networkidle'});await ready()
  assert.match(await page.getByTestId('capability-count').textContent(),new RegExp('已读取 '+real.capabilities.rows.length+' 项'))
  await mkdir('.runtime',{recursive:true});await page.screenshot({path:'.runtime/capability-catalog-real.png',fullPage:true})

  const resourceId=i=>'RES_'+(i+1).toString(16).padStart(32,'0')
  const capId=i=>'CAP_'+(i+1).toString(16).padStart(32,'0')
  const resources=Array.from({length:451},(_,i)=>({resource_id:resourceId(i),resource_name:i===450?'第451项已发布资源':'分页资源 '+i,status:i===450||i%3?'PUBLISHED':'DRAFT',physical_table:'container_event'}))
  const capabilities=Array.from({length:451},(_,i)=>({capability_id:capId(i),capability_name:i===450?'跨页目标能力':'分页能力 '+String(i).padStart(3,'0'),resource_id:resourceId(450),resource_name:'第451项已发布资源',
    type:['REST_API','KAFKA_TOPIC','CDC_STREAM','READONLY_QUERY'][i%4],http_method:'GET',endpoint:'/containers/events',protocol:'HTTP',auth_type:'NONE',topic:'port.event.v1',
    status:['DRAFT','VERIFIED','ENABLED','DISABLED','RETIRED'][i%5],health_status:i%3?'UP':'DOWN',version:1}))
  let fault='',submissionMode='fail',posts=0,guideCalls=0
  const seen=[]
  await page.route('**/api/v1/**',async route=>{
    const request=route.request(),url=new URL(request.url()),parts=url.pathname.split('/'),key=parts[3],id=parts[4]
    if(!['capabilities','resources'].includes(key)) return route.continue()
    if(request.method()!=='GET'){
      assert.equal(key,'capabilities');assert.equal(id,undefined);posts++
      if(submissionMode==='hold') await new Promise(resolve=>{releaseSave=resolve})
      return route.abort('failed') // Never forward fixture writes to the real service.
    }
    if(id && parts[5]==='invocation'){
      const sequence=++guideCalls
      if(sequence===1) await new Promise(resolve=>{releaseOld=resolve})
      return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:0,message:'success',data:{capability_id:id,http_method:'GET',provider_base_url:'http://127.0.0.1:18081/',endpoint:'/containers/events',request_url:'http://127.0.0.1:18081/containers/events',header_name:'X-Capability-Id',header_value:id,enabled:false,reason:sequence===1?'旧的调用说明':'新的调用说明'}})})
    }
    const offset=Number(url.searchParams.get('offset')),limit=Number(url.searchParams.get('limit'))
    seen.push({key,offset,limit})
    if(fault===key && offset===200) return route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:10001,message:'验收故障',data:null})})
    return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:0,message:'success',data:(key==='capabilities'?capabilities:resources).slice(offset,offset+limit)})})
  })
  await refresh()
  assert.match(await page.getByTestId('capability-count').textContent(),/已读取 451 项 · 匹配 451 项/)
  assert.ok(seen.some(r=>r.key==='resources'&&r.offset===400))
  assert.equal(await table().getByRole('row').count(),11)
  await page.locator('.capability-pagination .el-pager li').filter({hasText:/^2$/}).click()
  await table().getByText('分页能力 010',{exact:true}).waitFor()
  await page.getByLabel('关键词',{exact:true}).fill('跨页 第451项')
  await table().getByText('跨页目标能力',{exact:true}).waitFor()
  assert.match(await page.getByTestId('capability-count').textContent(),/匹配 1 项/)
  await page.screenshot({path:'.runtime/capability-catalog-search.png',fullPage:true})
  await page.getByRole('button',{name:'重置筛选',exact:true}).click()
  for(const [label,option] of [['能力类型','REST 接口'],['能力状态','草稿'],['健康状态','异常']]){
    const field=page.getByLabel(label,{exact:true});await field.focus();await field.press('ArrowDown');await page.getByRole('option',{name:option,exact:true}).click()
  }
  assert.match(await page.getByTestId('capability-count').textContent(),/匹配 8 项/)
  await page.screenshot({path:'.runtime/capability-catalog-filtered.png',fullPage:true})
  await page.getByRole('button',{name:'重置筛选',exact:true}).click()

  const row=table().getByRole('row').filter({hasText:'分页能力 000'})
  await row.getByRole('button',{name:'查看',exact:true}).click()
  await page.waitForFunction(()=>document.querySelector('.runtime-guide p')?.textContent.includes('正在读取'))
  await page.getByRole('dialog').locator('.el-dialog__headerbtn').click()
  await row.getByRole('button',{name:'查看',exact:true}).click()
  await page.getByText('新的调用说明',{exact:true}).waitFor()
  releaseOld();releaseOld=null
  await page.waitForTimeout(100)
  assert.equal(await page.getByText('旧的调用说明',{exact:true}).count(),0)
  await page.getByRole('dialog').locator('.el-dialog__headerbtn').click()

  await register().click()
  const dialog=page.getByRole('dialog')
  const resourceSelect=dialog.getByLabel('所属资源',{exact:true})
  await resourceSelect.focus();await resourceSelect.press('ArrowDown')
  assert.equal(await page.getByRole('option',{name:'分页资源 0',exact:true}).count(),0,'Draft resource offered for registration')
  await page.getByRole('option',{name:'第451项已发布资源',exact:true}).click()
  await dialog.getByLabel('能力名称',{exact:true}).fill('保存失败验收能力')
  await dialog.getByRole('button',{name:'填入本机示例配置',exact:true}).click()
  await page.screenshot({path:'.runtime/capability-catalog-registration.png',fullPage:true})
  const storage=await page.evaluate(()=>JSON.stringify(Object.entries(localStorage)))
  submissionMode='hold'
  await dialog.getByRole('button',{name:'保存能力',exact:true}).click()
  await page.waitForFunction(()=>document.querySelector('.el-dialog__footer .el-button.is-loading'))
  assert.equal(await dialog.getByRole('button',{name:'取消',exact:true}).isDisabled(),true)
  // Dispatch twice to check the function guard independently of disabled button UI.
  await dialog.getByRole('button',{name:'保存能力',exact:true}).evaluate(button=>{button.dispatchEvent(new MouseEvent('click',{bubbles:true}));button.dispatchEvent(new MouseEvent('click',{bubbles:true}))})
  assert.equal(posts,1)
  releaseSave();releaseSave=null
  await page.getByText(/接口能力操作结果尚未确认/).waitFor()
  assert.equal(await dialog.getByLabel('能力名称',{exact:true}).inputValue(),'保存失败验收能力')
  assert.equal(await dialog.isVisible(),true)
  assert.equal(await page.evaluate(()=>JSON.stringify(Object.entries(localStorage))),storage)
  await dialog.getByRole('button',{name:'取消',exact:true}).click()

  fault='resources';await refresh()
  await page.getByTestId('capability-resource-error').waitFor()
  assert.equal(await register().isDisabled(),true)
  assert.equal(await table().getByRole('row').count(),11)
  assert.equal(await row.getByRole('button',{name:'编辑',exact:true}).isDisabled(),true)
  fault='capabilities';await refresh()
  await page.getByTestId('capability-list-error').waitFor()
  assert.equal(await page.getByTestId('capability-table').count(),0)
  assert.equal(await page.getByTestId('demo-notice').count(),0)
  await page.screenshot({path:'.runtime/capability-catalog-failure.png',fullPage:true})
  fault='';await refresh()
  assert.equal(await page.getByTestId('capability-list-error').count(),0)
  assert.match(await page.getByTestId('capability-count').textContent(),/已读取 451 项/)
  assert.deepEqual(errors,[])
  console.log('Capability catalog passed: real counts, 451 rows/resources, filters/paging, published-only selection, stale same-ID guide ignored, duplicate save prevented, failed save preserved, isolated read failures/recovery. All fixture writes intercepted.')
} catch(error){await page?.screenshot({path:'.runtime/capability-catalog-error.png',fullPage:true}).catch(()=>{});throw error}
finally{releaseOld?.();releaseSave?.();await browser?.close();await server.close()}
