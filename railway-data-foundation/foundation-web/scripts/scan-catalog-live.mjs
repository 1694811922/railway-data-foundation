import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
import { chromium } from 'playwright-core'
import { loadScanCatalog } from '../src/utils/scanCatalog.js'

process.env.VITE_USE_MOCK='false'
const api='http://127.0.0.1:18080/api/v1'
const real=await loadScanCatalog(Object.fromEntries(['tasks','sources'].map(key=>[key,async params=>{
  const response=await fetch(api+'/'+(key==='tasks'?'scan-tasks':key)+'?'+new URLSearchParams(params))
  assert.ok(response.ok);const body=await response.json();assert.equal(body.code,0);return body.data
}])))
assert.equal(real.tasks.error||real.sources.error,false,'Start Foundation before this test')
const server=await createServer({server:{host:'127.0.0.1',port:5181,strictPort:true}})
let browser,page,releaseTable,releaseField,releaseScan
try{
  await server.listen()
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  page=await browser.newPage({viewport:{width:1660,height:1100},locale:'zh-CN'})
  const errors=[];page.on('pageerror',e=>errors.push(e.message))
  async function waitGate(getGate){
    const deadline=Date.now()+10000
    while(!getGate()){assert.ok(Date.now()<deadline,'Expected intercepted request did not arrive');await new Promise(resolve=>setTimeout(resolve,20))}
  }
  const taskId=i=>'SCAN_'+(i+1).toString(16).padStart(32,'0')
  const sourceId=i=>'SRC_'+(i+1).toString(16).padStart(32,'0')
  const tasks=Array.from({length:451},(_,i)=>({task_id:taskId(i),source_id:sourceId(i===450?450:0),source_name:i===450?'跨页港口来源':'任务来源 '+i,
    status:i===450?'SUCCESS':['SUCCESS','FAILED','RUNNING','PENDING'][i%4],table_count:2,field_count:3,
    started_at:'2026-10-03T01:00:00Z',finished_at:'2026-10-03T01:00:02Z',failure_reason:i%4===1?'测试权限不足':''}))
  const sources=Array.from({length:451},(_,i)=>({source_id:sourceId(i),source_name:i===450?'第451项已启用来源':'扫描来源 '+i,status:i===0?'DISABLED':'ENABLED'}))
  let fault='',holdTable=false,holdField=false,fieldLabel='原始字段',posts=0
  const seen=[]
  async function ready(){await page.getByRole('heading',{name:'元数据扫描',exact:true}).waitFor();await page.waitForFunction(()=>!document.querySelector('.el-button.is-loading')&&!document.querySelector('.el-loading-mask'))}
  async function refresh(){await page.getByRole('button',{name:'刷新任务',exact:true}).click();await ready()}
  const taskCard=i=>page.locator('[data-task-id="'+taskId(i)+'"]')
  const table=()=>page.getByTestId('scan-table-list')
  const selectTask=async i=>{await taskCard(i).getByRole('button',{name:'查看扫描结果',exact:true}).click();await ready()}
  const fields=()=>page.getByTestId('scan-field-list')
  async function screenshot(name){
    if(await page.locator('.el-message').count())await page.locator('.el-message').last().waitFor({state:'hidden'})
    await page.screenshot({path:'.runtime/'+name+'.png',fullPage:true,animations:'disabled'})
  }
  await page.goto('http://127.0.0.1:5181/',{waitUntil:'networkidle'});await page.getByRole('button',{name:'进入平台',exact:true}).click()
  await page.goto('http://127.0.0.1:5181/scans',{waitUntil:'networkidle'});await ready()
  assert.match(await page.getByTestId('scan-task-count').textContent(),new RegExp('已读取 '+real.tasks.rows.length+' 项'))
  await mkdir('.runtime',{recursive:true});await screenshot('scan-catalog-real')
  await page.route('**/api/v1/**',async route=>{
    const request=route.request(),url=new URL(request.url()),parts=url.pathname.split('/'),key=parts[3],id=parts[4]
    if(!['scan-tasks','sources'].includes(key))return route.continue()
    const fulfill=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({code:status===200?0:10001,message:status===200?'success':'测试读取故障',data})})
    if(request.method()!=='GET'){
      assert.equal(key,'sources');assert.equal(parts[5],'scan');posts++
      await new Promise(resolve=>{releaseScan=resolve});return route.abort('failed') // Never forward fixture writes.
    }
    if(key==='scan-tasks'&&parts[5]==='tables'){
      if(parts[7]==='fields'){
        const label=fieldLabel
        if(holdField){holdField=false;await new Promise(resolve=>{releaseField=resolve})}
        if(fault==='fields')return fulfill(null,503)
        return fulfill([{field_name:'id',data_type:'bigint',primary_key:true,nullable:false,technical_comment:label},
          {field_name:'event_time',column_type:'datetime(3)',nullable:false,technical_comment:'作业发生时间'},
          {field_name:'container_no',column_type:'varchar(20)',nullable:true,technical_comment:'集装箱号'}])
      }
      if(holdTable&&id===taskId(0)){holdTable=false;await new Promise(resolve=>{releaseTable=resolve})}
      if(fault==='tables')return fulfill(null,503)
      return fulfill([{table_name:'container_event',schema_name:'port_source_db',table_comment:'事件表 '+id,field_count:3,estimated_rows:20},
        {table_name:'train_dispatch',schema_name:'port_source_db',table_comment:'调度表',field_count:3,estimated_rows:12}])
    }
    if(key==='scan-tasks'&&id){
      seen.push({key,id});return id===taskId(999)?fulfill({...tasks[0],task_id:id,source_name:'范围外指定任务'}):fulfill(null,404)
    }
    const offset=Number(url.searchParams.get('offset')),limit=Number(url.searchParams.get('limit'));seen.push({key,offset})
    if(fault===key&&offset===200)return fulfill(null,503)
    return fulfill((key==='sources'?sources:tasks).slice(offset,offset+limit))
  })
  await refresh()
  assert.match(await page.getByTestId('scan-task-count').textContent(),/已读取 451 项 · 匹配 451 项/)
  assert.ok(seen.some(r=>r.key==='scan-tasks'&&r.offset===400));assert.ok(seen.some(r=>r.key==='sources'&&r.offset===400))
  assert.equal(await page.locator('[data-task-id]').count(),10)
  await page.getByLabel('任务关键词',{exact:true}).fill('跨页 港口');await selectTask(450)
  assert.match(await page.locator('.selected-summary').textContent(),new RegExp(taskId(450)))
  await page.getByLabel('扫描表关键词',{exact:true}).fill('container 事件');assert.equal(await table().getByRole('row').count(),2)
  await page.getByRole('button',{name:'查看字段',exact:true}).click()
  await page.getByText('container_no',{exact:true}).waitFor()
  await page.getByLabel('扫描字段关键词',{exact:true}).fill('time 作业');assert.equal(await fields().getByRole('row').count(),2)
  await screenshot('scan-catalog-fields')
  await page.getByRole('dialog').locator('.el-drawer__close-btn').click()
  await page.getByRole('button',{name:'重置筛选',exact:true}).click()
  const statusSelect=page.getByLabel('任务状态',{exact:true});await statusSelect.focus();await statusSelect.press('ArrowDown')
  await page.getByRole('option',{name:'失败',exact:true}).click()
  assert.match(await page.getByTestId('scan-task-count').textContent(),/匹配 113 项/)
  await selectTask(1);assert.equal(await table().getByRole('row').count(),1)
  await page.getByRole('button',{name:'重置筛选',exact:true}).click()
  // Selected task is refreshed from current metadata, and terminal states discard old tables.
  await selectTask(0);tasks[0].status='RUNNING';await refresh()
  await page.getByText('任务尚未完成，请刷新任务查看最新状态',{exact:true}).waitFor()
  assert.equal(await table().getByRole('row').count(),1)
  tasks[0].status='SUCCESS';await refresh()
  holdTable=true;await taskCard(0).getByRole('button',{name:'查看扫描结果',exact:true}).click()
  await waitGate(()=>releaseTable)
  await selectTask(4);releaseTable();releaseTable=null
  await page.waitForTimeout(100)
  assert.match(await table().textContent(),new RegExp('事件表 '+taskId(4)))
  assert.equal((await table().textContent()).includes('事件表 '+taskId(0)),false)
  fault='tables';await page.getByRole('button',{name:'重新加载结果',exact:true}).click();await ready()
  await page.getByTestId('scan-table-error').waitFor();assert.equal(await table().count(),0)
  fault='';await page.getByRole('button',{name:'重新加载结果',exact:true}).click();await ready()
  fault='fields';await page.getByRole('button',{name:'查看字段',exact:true}).first().click();await page.getByTestId('scan-field-error').waitFor()
  assert.equal(await page.getByRole('button',{name:'从此表创建资源',exact:true}).isDisabled(),true)
  fault='';await page.getByRole('button',{name:'重新加载字段',exact:true}).click();await page.getByText('原始字段',{exact:true}).waitFor()
  await page.getByRole('dialog').locator('.el-drawer__close-btn').click()
  holdField=true;fieldLabel='旧字段响应';await page.getByRole('button',{name:'查看字段',exact:true}).first().click()
  await waitGate(()=>releaseField)
  await page.getByRole('dialog').locator('.el-drawer__close-btn').click()
  fieldLabel='新字段响应';await page.getByRole('button',{name:'查看字段',exact:true}).first().click();await page.getByText('新字段响应',{exact:true}).waitFor()
  releaseField();releaseField=null;await page.waitForTimeout(100)
  assert.equal(await page.getByText('旧字段响应',{exact:true}).count(),0)
  await page.getByRole('dialog').locator('.el-drawer__close-btn').click()
  // Route updates on the same mounted view must resolve the requested task, including outside the bound.
  async function routeTask(id){await page.evaluate(value=>{history.pushState({},'', '/scans?task='+value);dispatchEvent(new PopStateEvent('popstate'))},id);await ready()}
  await routeTask(taskId(999));await page.getByText('范围外指定任务',{exact:true}).first().waitFor()
  assert.ok(seen.some(r=>r.id===taskId(999)))
  await routeTask('SCAN_missing');await page.getByTestId('scan-selection-error').waitFor()
  assert.equal(await page.locator('.selected-summary').count(),0)
  await selectTask(4);assert.equal(await page.getByTestId('scan-selection-error').count(),0)
  await routeTask(taskId(0));await page.locator('.selected-summary').waitFor()
  const sourceSelect=page.locator('.scan-actions .el-select input')
  await sourceSelect.focus();await sourceSelect.press('ArrowDown')
  assert.equal(await page.getByRole('option',{name:'扫描来源 0',exact:true}).getAttribute('aria-disabled'),'true')
  await page.getByRole('option',{name:'第451项已启用来源',exact:true}).click()
  const storage=await page.evaluate(()=>JSON.stringify(Object.entries(localStorage)))
  const scanButton=page.getByRole('button',{name:'开始扫描',exact:true});await scanButton.click()
  await waitGate(()=>releaseScan)
  await scanButton.evaluate(button=>{button.dispatchEvent(new MouseEvent('click',{bubbles:true}));button.dispatchEvent(new MouseEvent('click',{bubbles:true}))})
  assert.equal(posts,1);releaseScan();releaseScan=null
  await page.getByText('扫描请求结果尚未确认，请刷新任务检查，不要重复发起扫描',{exact:true}).waitFor()
  assert.equal(await page.evaluate(()=>JSON.stringify(Object.entries(localStorage))),storage)
  fault='sources';await refresh();await page.getByTestId('scan-source-error').waitFor()
  assert.equal(await scanButton.isDisabled(),true);assert.equal(await table().getByRole('row').count(),3)
  fault='scan-tasks';await refresh();await page.getByTestId('scan-task-error').waitFor()
  assert.equal(await page.locator('[data-task-id]').count(),0);assert.equal(await table().getByRole('row').count(),1)
  await screenshot('scan-catalog-failure')
  fault='';await refresh();await screenshot('scan-catalog-workspace')
  assert.deepEqual(errors,[])
  console.log('Scan catalog passed: real counts; 451 tasks/sources; task/table/field filters; refresh statuses; stale table/field isolation; direct task routes; failure/retry; duplicate scan prevented. Fixture writes intercepted.')
}catch(error){await page?.screenshot({path:'.runtime/scan-catalog-error.png',fullPage:true}).catch(()=>{});throw error}
finally{releaseTable?.();releaseField?.();releaseScan?.();await browser?.close();await server.close()}
