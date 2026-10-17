import assert from 'node:assert/strict'
import { mkdir,readFile,writeFile } from 'node:fs/promises'
import { createServer } from 'vite'
import { chromium } from 'playwright-core'
process.env.VITE_USE_MOCK='false'
const api='http://127.0.0.1:18080/api/v1'
async function get(path){const response=await fetch(api+path),body=await response.json();assert.ok(response.ok);assert.equal(body.code,0);return body.data}
const initial=await get('/subjects?limit=200')
const code='UI_V03_'+crypto.randomUUID().replaceAll('-','').toUpperCase(),name='UI_V03_共享主体测试'
await mkdir('.runtime',{recursive:true})
const manifest='.runtime/subject-live-fixtures.json'
let fixtures=[];try{fixtures=JSON.parse(await readFile(manifest,'utf8'))}catch(e){if(e.code!=='ENOENT')throw e}
fixtures.push({subject_code:code});await writeFile(manifest,JSON.stringify(fixtures,null,2))
const server=await createServer({server:{host:'127.0.0.1',port:5188,strictPort:true}})
let browser,release
try {
  await server.listen();browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
  const page=await browser.newPage({viewport:{width:1800,height:1080},locale:'zh-CN'}),errors=[]
  page.on('pageerror',e=>errors.push(e.message))
  const table=()=>page.getByTestId('subject-table'),dialog=()=>page.getByRole('dialog',{name:/共享主体/})
  async function ready(){await page.getByRole('heading',{name:'共享主体管理',exact:true}).waitFor();await page.waitForFunction(()=>!document.querySelector('.el-loading-mask')&&!document.querySelector('.el-button.is-loading'))}
  async function navigate(path){await page.evaluate(path=>{history.pushState({},'',path);dispatchEvent(new PopStateEvent('popstate'))},path)}
  async function refresh(){await page.getByRole('button',{name:'刷新列表',exact:true}).click();await ready()}
  async function fill(body){for(const [label,value] of Object.entries(body))await dialog().locator('.el-form-item').filter({has:page.locator('.el-form-item__label').filter({hasText:new RegExp('^\\*?\\s*'+label+'$')})}).locator('input,textarea').fill(value)}
  async function closeSaved(){await dialog().waitFor({state:'hidden'});await ready()}
  async function waitGate(){const end=Date.now()+10000;while(!release){assert.ok(Date.now()<end);await new Promise(resolve=>setTimeout(resolve,20))}}
  await page.goto('http://127.0.0.1:5188/',{waitUntil:'networkidle'});await page.getByRole('button',{name:'进入平台',exact:true}).click()
  await page.getByRole('menuitem',{name:'系统管理',exact:true}).click();await page.getByRole('menuitem',{name:'共享主体管理',exact:true}).click();await ready()
  assert.match(await page.getByTestId('subject-count').textContent(),new RegExp(initial.length+' 项匹配记录'))
  await page.getByRole('button',{name:'新增主体',exact:true}).click()
  await fill({'业务编码':code,'主体名称':name,'责任单位':'UI_V03_测试责任单位','业务说明':'仅测试登记，不授予数据权限'})
  await dialog().getByRole('button',{name:'保存主体',exact:true}).click();await closeSaved()
  await page.getByRole('textbox',{name:'搜索主体',exact:true}).fill(code)
  const row=()=>table().getByRole('row').filter({hasText:code})
  await row().getByText(name,{exact:true}).waitFor()
  await row().getByRole('button',{name:'编辑',exact:true}).click()
  assert.equal(await dialog().locator('.el-form-item').filter({hasText:'业务编码'}).locator('input').isDisabled(),true)
  await fill({'主体名称':name+'已编辑'});await dialog().getByRole('button',{name:'保存主体',exact:true}).click();await closeSaved()
  await row().getByRole('button',{name:'停用',exact:true}).click();await page.getByRole('button',{name:'确认',exact:true}).click();await ready()
  await row().getByText('已停用',{exact:true}).waitFor()
  const saved=(await get('/subjects?limit=200')).find(row=>row.subject_code===code)
  assert.equal(saved.version,3);assert.equal(saved.status,'DISABLED');assert.equal(saved.subject_name,name+'已编辑')
  if(await page.locator('.el-message').count())await page.locator('.el-message').last().waitFor({state:'hidden'})
  await page.screenshot({path:'.runtime/subject-real.png',fullPage:true,animations:'disabled'})

  let mode='',holdRead=false,holdWrite=false,writes=0
  const rows=Array.from({length:451},(_,i)=>({subject_id:'SUB_'+(i+1).toString(16).padStart(32,'0'),subject_code:'TEST_SUBJECT_'+i,subject_name:'治理主体'+i,subject_type:i%2?'DEMAND':'PROVIDER',owner_department:'责任单位',description:'夹具登记',status:i%3?'ENABLED':'DISABLED',version:1,created_at:'2026-10-04T00:00:00',updated_at:'2026-10-04T00:00:00'}))
  const seen=[]
  await page.route('**/api/v1/**',async route=>{
    const url=new URL(route.request().url()),method=route.request().method()
    if(!url.pathname.startsWith('/api/v1/subjects'))return route.continue()
    const reply=(data,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify({code:status===200?0:10002,message:'测试版本冲突，请刷新',data})})
    if(method==='GET'){
      if(holdRead){holdRead=false;await new Promise(resolve=>{release=resolve})}
      if(mode==='error')return reply(null,503)
      let list=mode==='bound'?Array.from({length:5001},(_,i)=>({...rows[0],subject_id:'SUB_'+(i+1).toString(16).padStart(32,'0'),subject_code:'SUBJECT_'+i})):rows
      if(mode==='demo')list=list.map(row=>({...row,_demo:true}))
      const offset=Number(url.searchParams.get('offset')),limit=Number(url.searchParams.get('limit'));seen.push(offset)
      return reply(list.slice(offset,offset+limit))
    }
    writes++;const body=route.request().postDataJSON()
    if(holdWrite){holdWrite=false;await new Promise(resolve=>{release=resolve})}
    if(mode==='conflict')return reply(null,400)
    if(mode==='timeout')return route.abort('failed')
    const current=rows.find(row=>url.pathname.includes(row.subject_id))
    return reply(current?{...current,...body,version:current.version+1}:{...rows[0],...body,subject_id:'SUB_'+'f'.repeat(32),version:1,status:'ENABLED'})
  })
  await page.getByRole('textbox',{name:'搜索主体',exact:true}).fill('');await refresh()
  assert.match(await page.getByTestId('subject-count').textContent(),/451 项/);assert.ok(seen.includes(400));assert.equal(await table().getByRole('row').count(),11)
  await page.locator('.el-pagination .btn-next').click();assert.match(await table().getByRole('row').nth(1).textContent(),/治理主体10/)
  await page.getByRole('textbox',{name:'搜索主体',exact:true}).fill('治理主体450');assert.equal(await table().getByRole('row').count(),2)
  await page.getByRole('textbox',{name:'搜索主体',exact:true}).fill('');await page.locator('.el-select').filter({has:page.getByRole('combobox',{name:'筛选角色',exact:true})}).click();await page.getByRole('option',{name:'需求方',exact:true}).click()
  assert.match(await page.getByTestId('subject-count').textContent(),/225 项/)
  await table().getByRole('button',{name:'编辑',exact:true}).first().click();await fill({'主体名称':'改名测试'})
  mode='conflict';await dialog().getByRole('button',{name:'保存主体',exact:true}).click();await dialog().getByTestId('subject-form-error').getByText(/测试版本冲突/).waitFor();assert.equal(await dialog().isVisible(),true)
  mode='timeout';await dialog().getByRole('button',{name:'保存主体',exact:true}).click();await dialog().getByTestId('subject-form-error').getByText(/请求结果未确认/).waitFor()
  assert.equal(writes,2,'No automatic retry after conflict/uncertain write')
  await dialog().getByRole('button',{name:'取消',exact:true}).click()
  mode='error';await refresh();await page.getByTestId('subject-error').waitFor();assert.equal(await table().getByRole('row').count(),1);assert.equal(await page.getByRole('button',{name:'新增主体',exact:true}).isDisabled(),true)
  mode='demo';await refresh();await page.getByTestId('subject-error').waitFor()
  mode='bound';await refresh();await page.getByText('仅统计前 5000 条记录',{exact:true}).waitFor()
  mode='';holdRead=true;release=undefined;await page.getByRole('button',{name:'刷新列表',exact:true}).click();await waitGate();await navigate('/about');await page.getByRole('heading',{name:'系统说明',exact:true}).waitFor();release();release=undefined
  await navigate('/subjects');await ready();await table().getByRole('button',{name:'编辑',exact:true}).first().click()
  holdWrite=true;await dialog().getByRole('button',{name:'保存主体',exact:true}).click();await waitGate()
  assert.equal(await dialog().locator('input').first().isDisabled(),true);assert.equal(await dialog().getByRole('button',{name:'取消',exact:true}).isDisabled(),true)
  await navigate('/about');release();release=undefined;await page.getByRole('heading',{name:'系统说明',exact:true}).waitFor()
  await navigate('/subjects');await ready();assert.equal(await page.getByRole('dialog').count(),0)
  assert.deepEqual(errors,[])
  console.log('Subject UI passed: real create/edit/disable; paging/filtering; conflict/uncertain write; fail closed; bounded reads; stale response isolation.')
} finally { release?.();await browser?.close();await server.close() }
