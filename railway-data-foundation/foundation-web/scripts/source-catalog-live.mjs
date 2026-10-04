import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { createServer } from 'vite'
import { chromium } from 'playwright-core'
import { loadSourceCatalog } from '../src/utils/sourceCatalog.js'

process.env.VITE_USE_MOCK='false'
const api='http://127.0.0.1:18080/api/v1'
const real=await loadSourceCatalog(async params=>{
  const response=await fetch(api+'/sources?'+new URLSearchParams(params));assert.ok(response.ok)
  const body=await response.json();assert.equal(body.code,0);return body.data
})
assert.equal(real.error,false,'Start Foundation before this test')
const server=await createServer({server:{host:'127.0.0.1',port:5182,strictPort:true}})
let browser,page,releaseSave,releaseTest,releaseScan
try{
  await server.listen()
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  page=await browser.newPage({viewport:{width:1660,height:1100},locale:'zh-CN'})
  const errors=[];page.on('pageerror',e=>errors.push(e.message))
  async function ready(){await page.getByRole('heading',{name:'数据源管理',exact:true}).waitFor();await page.waitForFunction(()=>!document.querySelector('.el-button.is-loading')&&!document.querySelector('.el-loading-mask'))}
  async function refresh(){await page.getByRole('button',{name:'刷新列表',exact:true}).click();await ready()}
  async function waitGate(getGate){const deadline=Date.now()+10000;while(!getGate()){assert.ok(Date.now()<deadline,'Expected request did not arrive');await new Promise(resolve=>setTimeout(resolve,20))}}
  async function screenshot(name){if(await page.locator('.el-message').count())await page.locator('.el-message').last().waitFor({state:'hidden'});await page.screenshot({path:'.runtime/'+name+'.png',fullPage:true,animations:'disabled'})}
  const table=()=>page.getByTestId('source-table'),newButton=()=>page.getByRole('button',{name:'＋ 新增数据源',exact:true})
  await page.goto('http://127.0.0.1:5182/',{waitUntil:'networkidle'});await page.getByRole('button',{name:'进入平台',exact:true}).click()
  await page.goto('http://127.0.0.1:5182/sources',{waitUntil:'networkidle'});await ready()
  assert.match(await page.getByTestId('source-count').textContent(),new RegExp('已读取 '+real.rows.length+' 项'))
  await mkdir('.runtime',{recursive:true});await screenshot('source-catalog-real')
  const sourceId=i=>'SRC_'+(i+1).toString(16).padStart(32,'0')
  const rows=Array.from({length:451},(_,i)=>({source_id:sourceId(i),source_name:i===450?'跨页广州港来源':'分页来源 '+String(i).padStart(3,'0'),
    source_type:i%2?'ORACLE':'MYSQL',status:i===450||i%3?'ENABLED':'DISABLED',host:'127.0.0.1',port:13306,database_name:'port_source_db',
    username:'fixture_reader',connection_mode:i===2?'ENV_REFERENCE':'DYNAMIC',ssl_mode:'DISABLED',description:'元数据读取测试',updated_at:'2026-10-04T00:00:00Z'}))
  let listFault='',testMode='success',testCalls=0,saveCalls=0,holdSave=false,lastBody=null,scanCalls=0,statusCalls=0,holdScan=false
  const seen=[]
  await page.route('**/api/v1/**',async route=>{
    const request=route.request(),url=new URL(request.url()),parts=url.pathname.split('/'),id=parts[4],action=parts[5]
    if(parts[3]!=='sources')return route.continue()
    const fulfill=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({code:status===200?0:10001,message:status===200?'success':'测试故障',data})})
    if(request.method()==='GET'){
      const offset=Number(url.searchParams.get('offset')),limit=Number(url.searchParams.get('limit'));seen.push(offset)
      if(listFault==='first'||(listFault==='second'&&offset===200))return fulfill(null,503)
      return fulfill(rows.slice(offset,offset+limit))
    }
    // No fixture operation can reach the actual backend.
    if(action==='test'){
      testCalls++
      if(testMode==='hold'){await new Promise(resolve=>{releaseTest=resolve});return fulfill({connected:true,database_product:'MySQL',database_version:'8.0.33',response_time_ms:13})}
      if(testMode==='network')return route.abort('failed')
      return fulfill({connected:testMode==='success',database_product:testMode==='success'?'MySQL':null,database_version:testMode==='success'?'8.0.33':null,response_time_ms:13,reason:testMode==='success'?'连接成功':'当前账号权限不足'})
    }
    if(action==='scan'){
      scanCalls++
      if(holdScan){await new Promise(resolve=>{releaseScan=resolve});return fulfill({task_id:'SCAN_'+ 'f'.repeat(32),source_id:id,status:'SUCCESS'})}
      return route.abort('failed')
    }
    if(action==='status'){statusCalls++;return route.abort('failed')}
    assert.ok(request.method()==='POST'||request.method()==='PUT')
    saveCalls++;lastBody=request.postDataJSON()
    if(holdSave)await new Promise(resolve=>{releaseSave=resolve})
    return route.abort('failed')
  })
  await refresh();assert.match(await page.getByTestId('source-count').textContent(),/已读取 451 项 · 匹配 451 项/)
  assert.ok(seen.includes(400));assert.equal(await table().getByRole('row').count(),11)
  await page.locator('.source-pagination .el-pagination button.btn-next').click()
  await page.getByText('分页来源 010',{exact:true}).waitFor()
  await page.getByLabel('数据源关键词',{exact:true}).fill('广州 PORT_SOURCE_DB');await page.getByText('跨页广州港来源',{exact:true}).waitFor()
  assert.equal(await table().getByRole('row').count(),2)
  await page.getByRole('button',{name:'重置筛选',exact:true}).click()
  for(const [label,option] of [['类型筛选','MySQL数据库'],['状态筛选','已停用']]){
    const select=page.getByLabel(label,{exact:true});await select.focus();await select.press('ArrowDown');await page.getByRole('option',{name:option,exact:true}).click()
  }
  assert.match(await page.getByTestId('source-count').textContent(),/匹配 75 项/)
  await screenshot('source-catalog-filtered')
  const disabled=table().getByRole('row').filter({hasText:'分页来源 000'})
  assert.equal(await disabled.getByRole('button',{name:'测试连接',exact:true}).isDisabled(),true)
  assert.equal(await disabled.getByRole('button',{name:'开始扫描',exact:true}).isDisabled(),true)
  await page.getByRole('button',{name:'重置筛选',exact:true}).click()
  await page.getByLabel('数据源关键词',{exact:true}).fill('跨页 广州')
  const row=table().getByRole('row').filter({hasText:'跨页广州港来源'})
  await row.getByRole('button',{name:'测试连接',exact:true}).click();await ready()
  await page.getByText('8.0.33',{exact:true}).waitFor()
  testMode='failed';await row.getByRole('button',{name:'测试连接',exact:true}).click();await ready()
  assert.equal(await page.getByText('8.0.33',{exact:true}).count(),0)
  await page.getByText('失败原因：当前账号权限不足',{exact:true}).waitFor()
  testMode='network';await row.getByRole('button',{name:'测试连接',exact:true}).click();await ready()
  await page.getByText('请求失败',{exact:true}).waitFor()
  assert.equal(await page.getByText('演示连接成功',{exact:true}).count(),0)
  await screenshot('source-catalog-connection-failure')
  testMode='hold';await row.getByRole('button',{name:'测试连接',exact:true}).click();await waitGate(()=>releaseTest)
  const calls=testCalls
  await row.getByRole('button',{name:'测试连接',exact:true}).evaluate(button=>{button.dispatchEvent(new MouseEvent('click',{bubbles:true}));button.dispatchEvent(new MouseEvent('click',{bubbles:true}))})
  assert.equal(testCalls,calls);releaseTest();releaseTest=null;await ready()
  await row.getByRole('button',{name:'编辑',exact:true}).click()
  const dialog=page.getByRole('dialog')
  assert.equal(await dialog.getByLabel('数据库密码',{exact:true}).inputValue(),'')
  const storage=await page.evaluate(()=>JSON.stringify(Object.entries(localStorage)))
  await dialog.getByLabel('备注说明',{exact:true}).fill('失败后保留的备注');holdSave=true
  await dialog.getByRole('button',{name:'保存',exact:true}).click();await waitGate(()=>releaseSave)
  assert.equal(await dialog.getByRole('button',{name:'取消',exact:true}).isDisabled(),true)
  assert.equal(await dialog.getByLabel('数据库地址',{exact:true}).isDisabled(),true)
  await dialog.getByRole('button',{name:'保存',exact:true}).evaluate(button=>{button.dispatchEvent(new MouseEvent('click',{bubbles:true}));button.dispatchEvent(new MouseEvent('click',{bubbles:true}))})
  assert.equal(saveCalls,1);assert.equal(Object.hasOwn(lastBody,'password'),false)
  assert.equal(Object.hasOwn(lastBody,'credential_ref'),false)
  releaseSave();releaseSave=null;holdSave=false
  await page.getByText(/操作结果尚未确认/).waitFor()
  assert.equal(await dialog.getByLabel('备注说明',{exact:true}).inputValue(),'失败后保留的备注')
  await dialog.getByRole('button',{name:'取消',exact:true}).click()
  await newButton().click()
  for(const [label,value] of [['数据源名称','注册失败测试'],['数据库地址','127.0.0.1'],['数据库名称','port_db'],['数据库用户名','reader'],['数据库密码','fixture-password-never-persist']])await dialog.getByLabel(label,{exact:true}).fill(value)
  await dialog.getByRole('button',{name:'保存',exact:true}).click();await page.getByText(/操作结果尚未确认/).last().waitFor()
  assert.equal(saveCalls,2);assert.equal(await dialog.getByLabel('数据库密码',{exact:true}).inputValue(),'fixture-password-never-persist')
  assert.equal(await page.evaluate(()=>JSON.stringify(Object.entries(localStorage))),storage)
  await dialog.getByRole('button',{name:'取消',exact:true}).click();await newButton().click()
  assert.equal(await dialog.getByLabel('数据库密码',{exact:true}).inputValue(),'')
  await dialog.getByRole('button',{name:'取消',exact:true}).click()
  await row.getByRole('button',{name:'停用',exact:true}).click();await ready()
  assert.equal(statusCalls,1);assert.equal(rows[450].status,'ENABLED')
  await row.getByRole('button',{name:'开始扫描',exact:true}).click();await ready()
  assert.equal(scanCalls,1);assert.equal(new URL(page.url()).pathname,'/sources')
  assert.equal(await page.evaluate(()=>JSON.stringify(Object.entries(localStorage))),storage)
  listFault='second';await refresh();await page.getByTestId('source-list-error').waitFor()
  assert.equal(await table().count(),0);assert.equal(await newButton().isDisabled(),true)
  assert.equal(await page.getByText('当前为演示数据',{exact:false}).count(),0)
  await screenshot('source-catalog-list-failure')
  listFault='first';await refresh();await page.getByTestId('source-list-error').waitFor()
  listFault='';await refresh();assert.match(await page.getByTestId('source-count').textContent(),/已读取 451 项/)
  await screenshot('source-catalog-search')
  holdScan=true;await row.getByRole('button',{name:'开始扫描',exact:true}).click();await waitGate(()=>releaseScan)
  await page.getByRole('menuitem',{name:'首页',exact:true}).click();await page.waitForURL('**/dashboard')
  releaseScan();releaseScan=null;await page.waitForTimeout(150)
  assert.equal(new URL(page.url()).pathname,'/dashboard','Late scan response navigated after leaving Sources')
  assert.deepEqual(errors,[])
  console.log('Source catalog passed: real count; 451 records; filters/paging; disabled-source guards; connection failures; duplicate save/test prevented; blank-password edit; failed writes preserve input and storage; read failures/recovery. All fixture writes intercepted.')
}catch(error){await page?.screenshot({path:'.runtime/source-catalog-error.png',fullPage:true,animations:'disabled'}).catch(()=>{});throw error}
finally{releaseSave?.();releaseTest?.();releaseScan?.();await browser?.close();await server.close()}
