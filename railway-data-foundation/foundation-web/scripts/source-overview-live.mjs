import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
import { chromium } from 'playwright-core'
import { loadSourceCatalog } from '../src/utils/sourceCatalog.js'
import { readSourceOverview } from '../src/utils/sourceOverview.js'

process.env.VITE_USE_MOCK='false'
const api='http://127.0.0.1:18080/api/v1'
async function get(path,params){const response=await fetch(api+path+'?'+new URLSearchParams(params)),body=await response.json();assert.ok(response.ok);assert.equal(body.code,0);return body.data}
const real=await loadSourceCatalog(params=>get('/sources',params));assert.equal(real.error,false);assert.ok(real.rows.length)
const realSource=real.rows[0]
const realOverview=Object.fromEntries(await Promise.all(['tasks','resources'].map(async key=>[key,await readSourceOverview(key,realSource,params=>get(key==='tasks'?'/scan-tasks':'/resources',params))])))
assert.equal(Object.values(realOverview).some(result=>result.error),false,'Start Foundation before this test')
const server=await createServer({server:{host:'127.0.0.1',port:5187,strictPort:true}})
let browser,page,release
try {
  await server.listen()
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  page=await browser.newPage({viewport:{width:1660,height:1100},locale:'zh-CN'})
  const errors=[],writes=[];page.on('pageerror',e=>errors.push(e.message))
  page.on('request',req=>{if(req.url().includes('/api/v1/')&&req.method()!=='GET')writes.push(req.method())})
  const drawer=()=>page.getByRole('dialog',{name:'数据源概览',exact:true})
  const section=key=>page.getByTestId('source-overview-'+key)
  async function ready(){await page.getByTestId('source-count').waitFor();await page.waitForFunction(()=>!document.querySelector('[data-testid="source-table"] .el-loading-mask'))}
  async function overviewReady(){
    await drawer().waitFor()
    await page.waitForFunction(()=>!document.querySelector('[data-testid="source-overview"] .el-loading-mask') && !document.querySelector('[data-testid="source-overview"] .el-button.is-loading'))
    await drawer().evaluate(async element=>{await Promise.all(element.getAnimations({subtree:true}).map(animation=>animation.finished.catch(()=>{})))})
  }
  async function open(name){await page.getByTestId('source-table').getByRole('row').filter({hasText:name}).getByRole('button',{name:'查看',exact:true}).click();await overviewReady()}
  async function close(){await drawer().getByRole('button',{name:'关闭',exact:true}).click();await drawer().waitFor({state:'hidden'})}
  async function sources(){await page.evaluate(()=>{history.pushState({},'','/sources');dispatchEvent(new PopStateEvent('popstate'))});await ready()}
  await page.goto('http://127.0.0.1:5187/',{waitUntil:'networkidle'});await page.getByRole('button',{name:'进入平台',exact:true}).click()
  await page.goto('http://127.0.0.1:5187/sources',{waitUntil:'networkidle'});await ready();await open(realSource.source_name)
  for(const key of ['tasks','resources'])assert.match(await page.getByTestId('source-overview-'+key+'-count').textContent(),new RegExp(realOverview[key].rows.length+' 项关联记录'))
  assert.equal((await drawer().textContent()).includes(realSource.credential_ref),false)
  await mkdir('.runtime',{recursive:true});await page.screenshot({path:'.runtime/source-overview-real.png',fullPage:true});await close()
  const ids=['SRC_'+ 'a'.repeat(32),'SRC_'+ 'b'.repeat(32)], names=['概览港口来源','概览铁路来源']
  const sourceRows=ids.map((source_id,i)=>({source_id,source_name:names[i],source_type:'MYSQL',status:'ENABLED',host:'127.0.0.1',port:13306,database_name:i?'dispatch_db':'port_source_db',username:'metadata_reader',ssl_mode:'DISABLED',connection_mode:'DYNAMIC',description:'仅结构元数据概览',credential_ref:'PRIVATE_OVERVIEW_REFERENCE',updated_at:'2026-10-04T01:00:00'}))
  const resourceRows=Array.from({length:451},(_,i)=>({resource_id:'RES_'+(i+1).toString(16).padStart(32,'0'),source_id:i>=440?ids[0]:ids[1],source_name:i>=440?names[0]:names[1],resource_name:i>=440?'港口关联资源 '+i:'铁路关联资源 '+i,physical_table:'container_event',status:'PUBLISHED',security_level:'内部',version:1,created_at:i===450?'2026-10-04T01:00:00':'2026-10-03T01:00:00'}))
  const taskRows=Array.from({length:451},(_,i)=>({task_id:'SCAN_'+(i+1).toString(16).padStart(32,'0'),source_id:i>=440?ids[0]:ids[1],source_name:i>=440?names[0]:names[1],status:i%2?'FAILED':'SUCCESS',table_count:1,field_count:12,started_at:i===450?'2026-10-04T01:00:00':'2026-10-03T01:00:00',finished_at:'2026-10-04T01:01:00',failure_reason:i%2?'元数据读取权限不足':''}))
  let fault='',hold=false,bounded=false
  const seen=[]
  await page.route('**/api/v1/**',async route=>{
    assert.equal(route.request().method(),'GET','Overview must not test/scan/save')
    const url=new URL(route.request().url()),parts=url.pathname.split('/'),key=parts[3]
    const fulfill=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({code:status===200?0:10001,message:'测试读取故障',data})})
    if(parts[4]) {
      if(key==='resources')return fulfill(parts[5]?[]:resourceRows.find(row=>row.resource_id===parts[4]))
      if(key==='scan-tasks')return fulfill(parts[5]?[]:taskRows.find(row=>row.task_id===parts[4]))
      throw new Error('Unexpected API')
    }
    if(hold&&key==='scan-tasks'){hold=false;await new Promise(resolve=>{release=resolve})}
    if(fault===key)return fulfill(null,503)
    let rows=key==='sources'?sourceRows:key==='resources'?resourceRows:key==='scan-tasks'?taskRows:[]
    if(fault==='mode'&&key==='resources')rows=resourceRows.map(row=>({...row,_demo:true}))
    if(bounded&&key==='resources')rows=Array.from({length:5001},(_,i)=>({...resourceRows[0],source_id:ids[0],resource_id:'RES_BOUND_'+i}))
    const offset=Number(url.searchParams.get('offset')),limit=Number(url.searchParams.get('limit'));seen.push({key,offset})
    return fulfill(rows.slice(offset,offset+limit))
  })
  await page.getByRole('button',{name:'刷新列表',exact:true}).click();await ready();await open(names[0])
  assert.match(await page.getByTestId('source-overview-resources-count').textContent(),/11 项关联记录/)
  assert.match(await page.getByTestId('source-overview-tasks-count').textContent(),/11 项关联记录/)
  assert.equal(await section('resources').getByRole('row').count(),6);assert.equal(await section('tasks').getByRole('row').count(),6)
  assert.match(await section('resources').getByRole('row').nth(1).textContent(),/港口关联资源 450/)
  assert.equal((await drawer().textContent()).includes('铁路关联资源'),false)
  assert.equal((await drawer().textContent()).includes('PRIVATE_OVERVIEW_REFERENCE'),false)
  assert.ok(seen.some(call=>call.key==='resources'&&call.offset===400));assert.ok(seen.some(call=>call.key==='scan-tasks'&&call.offset===400))
  await page.screenshot({path:'.runtime/source-overview-associated.png',fullPage:true})
  await section('resources').getByRole('button',{name:'查看资源',exact:true}).first().click()
  await page.waitForURL('**/resources/'+resourceRows[450].resource_id);await page.getByRole('heading',{name:'港口关联资源 450',exact:true}).waitFor()
  await sources();await open(names[0]);await section('tasks').getByRole('button',{name:'查看任务',exact:true}).first().click()
  await page.waitForURL('**/scans?task='+taskRows[450].task_id);await page.locator('.selected-summary').waitFor()
  await sources();await open(names[0]);await drawer().getByRole('button',{name:'查看来源资源目录',exact:true}).click()
  await page.waitForURL('**/resources?sourceFilter='+ids[0]);await page.getByTestId('catalog-count').waitFor()
  await page.getByTestId('catalog-count').filter({hasText:'匹配 11 项'}).waitFor()
  assert.match(await page.getByTestId('catalog-count').textContent(),/匹配 11 项/)
  await sources();await open(names[0]);fault='resources'
  await drawer().getByRole('button',{name:'刷新资源',exact:true}).click();await section('resources').getByRole('alert').waitFor()
  assert.equal(await section('resources').getByRole('row').count(),0);assert.equal(await section('tasks').getByRole('row').count(),6)
  fault='';await drawer().getByRole('button',{name:'刷新资源',exact:true}).click();await overviewReady();assert.equal(await section('resources').getByRole('row').count(),6)
  fault='mode';await drawer().getByRole('button',{name:'刷新资源',exact:true}).click();await section('resources').getByRole('alert').waitFor();assert.equal(await section('resources').getByRole('row').count(),0)
  fault='';bounded=true;await drawer().getByRole('button',{name:'刷新资源',exact:true}).click();await overviewReady()
  assert.match(await page.getByTestId('source-overview-resources-count').textContent(),/≥ 5000/);await section('resources').getByRole('alert').waitFor()
  await page.screenshot({path:'.runtime/source-overview-bounded.png',fullPage:true});bounded=false
  hold=true;await drawer().getByRole('button',{name:'刷新扫描',exact:true}).click()
  const deadline=Date.now()+10000;while(!release){assert.ok(Date.now()<deadline);await page.waitForTimeout(20)}
  await close();await open(names[1]);assert.equal((await drawer().textContent()).includes('港口关联资源'),false)
  release();release=null;await page.waitForTimeout(100)
  assert.equal((await drawer().textContent()).includes('港口关联资源'),false);assert.match(await page.getByTestId('source-overview-tasks-count').textContent(),/440 项关联记录/)
  await close();hold=true;await page.getByTestId('source-table').getByRole('row').filter({hasText:names[0]}).getByRole('button',{name:'查看',exact:true}).click()
  const deadline2=Date.now()+10000;while(!release){assert.ok(Date.now()<deadline2);await page.waitForTimeout(20)}
  await page.evaluate(()=>{history.pushState({},'','/about');dispatchEvent(new PopStateEvent('popstate'))});await page.getByRole('heading',{name:'系统说明',exact:true}).waitFor()
  release();release=null;await page.waitForTimeout(100);assert.equal(await drawer().count(),0)
  assert.deepEqual(writes,[]);assert.deepEqual(errors,[])
  console.log('Source overview passed: real associated counts; safe basic fields; 451-row reads; exact source isolation; latest five; resource/task/catalog links; independent faults/retry; mixed-mode rejection; 5000 bound; close/reopen and unmount isolation. GET only, no business queries or database writes.')
}catch(error){await page?.screenshot({path:'.runtime/source-overview-error.png',fullPage:true}).catch(()=>{});throw error}
finally{release?.();await browser?.close();await server.close()}
