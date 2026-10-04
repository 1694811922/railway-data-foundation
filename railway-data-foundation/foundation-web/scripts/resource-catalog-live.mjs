import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { chromium } from 'playwright-core'
import { mkdir } from 'node:fs/promises'
import { fetchDashboardList } from '../src/utils/dashboard.js'

process.env.VITE_USE_MOCK='false'
const api='http://127.0.0.1:18080/api/v1'
const real=await fetchDashboardList(async params=>{
  const response=await fetch(api+'/resources?'+new URLSearchParams(params))
  const body=await response.json()
  assert.equal(response.ok,true,'Start Foundation before running this test')
  assert.equal(body.code,0)
  return body.data
},'resource_id')
const server=await createServer({server:{host:'127.0.0.1',port:5179,strictPort:true}})
let browser, page
try {
  await server.listen()
  browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  page=await browser.newPage({viewport:{width:1600,height:1050},locale:'zh-CN'})
  const errors=[], writes=[], seen=[]
  page.on('pageerror',error=>errors.push(error.message))
  page.on('request',request=>{if(request.url().includes('/api/v1/') && request.method()!=='GET') writes.push(request.method())})
  const table=()=>page.getByTestId('catalog-table')
  const keyword=()=>page.getByLabel('关键词',{exact:true})
  async function ready(){
    await page.getByRole('heading',{name:'数据资源目录',exact:true}).waitFor()
    await page.waitForFunction(()=>!document.querySelector('.el-button.is-loading') && !document.querySelector('.el-loading-mask'))
  }
  async function refresh(){await page.getByRole('button',{name:'刷新目录',exact:true}).click();await ready()}
  async function query(){await page.getByRole('button',{name:'查询',exact:true}).click();await page.waitForTimeout(50)}
  async function select(label,option){
    const input=page.getByLabel(label,{exact:true})
    await input.focus(); await input.press('ArrowDown')
    await page.getByRole('option',{name:option,exact:true}).click()
  }
  await page.goto('http://127.0.0.1:5179/',{waitUntil:'networkidle'})
  await page.getByRole('button',{name:'进入平台',exact:true}).click()
  await page.goto('http://127.0.0.1:5179/resources',{waitUntil:'networkidle'})
  await ready()
  assert.match(await page.getByTestId('catalog-count').textContent(),new RegExp('已读取 '+real.rows.length+' 项'))
  await mkdir('.runtime',{recursive:true})
  await page.screenshot({path:'.runtime/resource-catalog-real.png',fullPage:true})
  const fixtures=Array.from({length:451},(_,i)=>({
    resource_id:'RES_CATALOG_'+i,resource_name:i===450?'跨页目标资源':'目录验收资源 '+String(i).padStart(3,'0'),
    source_id:i%2?'SRC_CATALOG_B':'SRC_CATALOG_A',source_name:i%2?'铁路业务库':'港口业务库',
    schema_name:'catalog_demo',physical_table:i===450?'container_event':'event_'+i,
    owner_department:'目录验收部门',business_description:'仅用于 HTTP 响应夹具，未写入真实数据库',resource_type:'TABLE',
    status:i%2?'DRAFT':'PUBLISHED',security_level:i%3?'内部':'敏感',update_mode:'实时',version:1,
    created_at:new Date().toISOString(),updated_at:new Date().toISOString(),
  }))
  let failure='',bounded=false
  await page.route('**/api/v1/**',async route=>{
    if(!new URL(route.request().url()).pathname.startsWith('/api/v1/resources')) return route.continue()
    assert.equal(route.request().method(),'GET','This test must never write resources')
    const url=new URL(route.request().url()), path=url.pathname
    const id=path.split('/')[4]
    if(id){
      const data=path.endsWith('/fields') || path.endsWith('/capabilities') ? [] : fixtures.find(row=>row.resource_id===id)
      return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:0,message:'success',data})})
    }
    const offset=Number(url.searchParams.get('offset')),limit=Number(url.searchParams.get('limit'))
    seen.push({offset,limit})
    if(failure==='first' || failure==='second' && offset===200)
      return route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:10001,message:'验收用列表故障',data:null})})
    const rows=bounded?Array.from({length:5001},(_,i)=>({...fixtures[i%fixtures.length],resource_id:'RES_BOUND_'+i})):fixtures
    return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({code:0,message:'success',data:rows.slice(offset,offset+limit)})})
  })
  await refresh()
  assert.match(await page.getByTestId('catalog-count').textContent(),/已读取 451 项 · 匹配 451 项/)
  assert.ok(seen.some(request=>request.offset===400))
  assert.equal(await table().getByRole('row').count(),11)
  await page.locator('.catalog-pagination .el-pager li').filter({hasText:/^2$/}).click()
  await page.waitForURL(/page=2/)
  await table().getByText('目录验收资源 010',{exact:true}).waitFor()

  // Details and browser back retain catalog criteria and page.
  await table().getByRole('row').filter({hasText:'目录验收资源 010'}).getByRole('button',{name:'查看',exact:true}).click()
  await page.getByRole('heading',{name:'目录验收资源 010',exact:true}).waitFor()
  await page.goBack({waitUntil:'networkidle'})
  await ready()
  assert.equal(new URL(page.url()).searchParams.get('page'),'2')
  await table().getByText('目录验收资源 010',{exact:true}).waitFor()

  await keyword().fill('CONTAINER 目录验收部门'); await query()
  await table().getByText('跨页目标资源',{exact:true}).waitFor()
  assert.match(await page.getByTestId('catalog-count').textContent(),/匹配 1 项/)
  assert.equal(new URL(page.url()).searchParams.has('page'),false)
  await page.reload({waitUntil:'networkidle'});await ready()
  assert.equal(await keyword().inputValue(),'CONTAINER 目录验收部门')
  await table().getByText('跨页目标资源',{exact:true}).waitFor()
  await table().getByRole('row').filter({hasText:'跨页目标资源'}).getByRole('button',{name:'查看',exact:true}).click()
  await page.getByRole('heading',{name:'跨页目标资源',exact:true}).waitFor()
  await page.getByRole('button',{name:/返回资源目录$/}).click()
  await ready()
  assert.equal(await keyword().inputValue(),'CONTAINER 目录验收部门')
  await table().getByText('跨页目标资源',{exact:true}).waitFor()
  await page.screenshot({path:'.runtime/resource-catalog-search.png',fullPage:true})

  await page.getByRole('button',{name:'重置',exact:true}).click()
  await select('来源系统','港口业务库'); await select('资源状态','已发布'); await select('安全等级','敏感'); await query()
  assert.match(await page.getByTestId('catalog-count').textContent(),/匹配 76 项/)
  await page.screenshot({path:'.runtime/resource-catalog-filtered.png',fullPage:true})
  await keyword().fill('没有这个资源');await query()
  await page.getByText('没有符合条件的资源，请调整筛选条件',{exact:true}).waitFor()
  await page.getByRole('button',{name:'重置',exact:true}).click()
  assert.equal(await keyword().inputValue(),'')
  await page.locator('.catalog-pagination .el-select').click()
  await page.getByRole('option',{name:'20条/页',exact:true}).click()
  await page.waitForURL(/pageSize=20/)
  assert.equal(await table().getByRole('row').count(),21)

  for(const fault of ['second','first']){
    failure=fault
    await refresh()
    await page.getByTestId('catalog-error').waitFor()
    assert.equal(await page.getByTestId('catalog-table').count(),0)
    assert.equal(await page.getByText('广州港集装箱作业事件数据集',{exact:true}).count(),0)
    assert.equal(await page.getByText('当前目录使用前端演示数据',{exact:false}).count(),0)
  }
  await page.screenshot({path:'.runtime/resource-catalog-failure.png',fullPage:true})
  failure='';await refresh()
  assert.equal(await page.getByTestId('catalog-error').count(),0)
  assert.equal(await table().getByRole('row').count(),21)
  bounded=true;await refresh()
  await page.getByTestId('catalog-incomplete').waitFor()
  assert.match(await page.getByTestId('catalog-count').textContent(),/已读取 5000 项/)
  assert.ok(seen.some(request=>request.offset===5000 && request.limit===1))
  assert.deepEqual(writes,[])
  assert.deepEqual(errors,[])
  console.log('Resource catalog passed: real API, 451 records, metadata search, intersecting filters, paging, URL reload/back, empty result, read failures/recovery, bounded scope. No database writes.')
} catch(error) {
  await page?.screenshot({path:'.runtime/resource-catalog-error.png',fullPage:true}).catch(()=>{})
  throw error
} finally {
  await browser?.close()
  await server.close()
}
