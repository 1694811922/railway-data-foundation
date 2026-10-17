import assert from 'node:assert/strict'
import {readFile,writeFile} from 'node:fs/promises'
import {createServer} from 'vite'
import {chromium} from 'playwright-core'
import path from 'node:path'
import net from 'node:net'
const c=JSON.parse(await readFile(process.argv[2],'utf8'));assert.ok(['127.0.0.1','localhost'].includes(new URL(c.foundation).hostname))
process.env.VITE_USE_MOCK='false'
const port=await new Promise(resolve=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>resolve(p))})})
const server=await createServer({cacheDir:path.join(c.evidence,'vite-lifecycle-cache'),server:{host:'127.0.0.1',port,strictPort:true,proxy:{'/api':{target:c.foundation,changeOrigin:true}}}})
let browser,page,release;const errors=[]
const names={unpublish:'下架资源',republish:'重新发布',retire:'退役资源'}
try{
 await server.listen();browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true});page=await browser.newPage({viewport:{width:1680,height:1080},locale:'zh-CN'});page.setDefaultTimeout(20000);page.on('pageerror',e=>errors.push(e.message))
 const base=`http://127.0.0.1:${port}`,api=base+'/api/v1/resources/'+c.resource_id
 await page.goto(base+'/login',{waitUntil:'networkidle'});await page.getByRole('textbox',{name:'登录账号',exact:true}).fill(process.env.RESOURCE_LIFE_USER);await page.getByRole('textbox',{name:'登录密码',exact:true}).fill(process.env.RESOURCE_LIFE_PASSWORD);await page.getByRole('button',{name:'进入平台',exact:true}).click();await page.waitForURL('**/dashboard')
 await page.goto(base+'/resources/'+c.resource_id,{waitUntil:'networkidle'})
 const dialog=()=>page.getByRole('dialog',{name:names[c.action]||/资源|重新发布/})
 if(!c.action){
  let releaseLate,announceLate;const lateStarted=new Promise(r=>announceLate=r),lateGate=new Promise(r=>releaseLate=r)
  await page.route('**/api/v1/resources/*/lifecycle-impact',async r=>{announceLate();await lateGate;await r.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:10006,message:'已关闭对话框的旧响应',data:null})})},{times:1})
  await page.getByRole('button',{name:'下架资源',exact:true}).click();await lateStarted;await dialog().getByRole('button',{name:'取消',exact:true}).click()
  await page.getByRole('button',{name:'下架资源',exact:true}).click();await dialog().getByText('关联接口能力',{exact:true}).waitFor()
  const lateReply=page.waitForResponse(r=>r.url().endsWith('/lifecycle-impact')&&r.status()===503);releaseLate();await lateReply;await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))))
  assert.equal(await dialog().getByTestId('resource-lifecycle-error').count(),0);await dialog().getByRole('button',{name:'取消',exact:true}).click()
  await page.route('**/api/v1/resources/*/lifecycle-impact',r=>r.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:10006,message:'独立影响读取故障',data:null})}),{times:1})
  await page.getByRole('button',{name:'下架资源',exact:true}).click();await page.getByTestId('resource-lifecycle-error').waitFor();assert.equal(await dialog().getByRole('button',{name:'确认下架资源',exact:true}).isDisabled(),true)
  await page.getByRole('button',{name:'重试读取影响',exact:true}).click();await dialog().getByText('关联接口能力',{exact:true}).waitFor();await dialog().getByRole('textbox',{name:'资源状态操作原因',exact:true}).fill('保留失败输入')
  await page.route('**/api/v1/resources/*/unpublish',r=>r.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:10006,message:'独立状态写入故障',data:null})}),{times:1})
  await dialog().getByRole('button',{name:'确认下架资源',exact:true}).click();await page.getByTestId('resource-lifecycle-error').waitFor();assert.equal(await dialog().getByRole('textbox',{name:'资源状态操作原因',exact:true}).inputValue(),'保留失败输入');assert.equal((await (await page.request.get(api)).json()).data.status,'PUBLISHED')
  // A real concurrent metadata edit makes the already-open lifecycle form stale.
  const csrf=(await (await page.request.get(base+'/api/v1/auth/csrf')).json()).data,current=(await (await page.request.get(api)).json()).data
  const edit=Object.fromEntries(['resource_name','source_id','scan_task_id','schema_name','physical_table','resource_type','business_description','owner_department','security_level','update_mode','version'].map(k=>[k,current[k]]));edit.business_description='独立并发元数据编辑'
  assert.equal((await page.request.put(api,{headers:{[csrf.header_name]:csrf.token},data:edit})).status(),200)
  await dialog().getByRole('button',{name:'确认下架资源',exact:true}).click();await dialog().getByText('请关闭对话框，刷新资源后重新核对；本次操作未提交。',{exact:true}).waitFor();assert.equal(await dialog().getByRole('button',{name:'确认下架资源',exact:true}).isDisabled(),true)
  await dialog().getByRole('button',{name:'取消',exact:true}).click();await page.getByRole('button',{name:'刷新详情',exact:true}).click();await page.getByRole('button',{name:'下架资源',exact:true}).waitFor()
 }
 for(const action of c.action?[c.action]:['unpublish','republish','unpublish','retire']){
  await page.getByRole('button',{name:names[action],exact:true}).click();const box=page.getByRole('dialog',{name:names[action],exact:true});await box.getByText('关联接口能力',{exact:true}).waitFor();await box.getByRole('textbox',{name:'资源状态操作原因',exact:true}).fill('真实浏览器生命周期验收')
  await box.getByRole('button',{name:'确认'+names[action],exact:true}).click();await box.getByText(names[action]+'已完成',{exact:true}).waitFor();const actual=(await (await page.request.get(api)).json()).data;assert.equal(actual.status,{unpublish:'OFFLINE',republish:'PUBLISHED',retire:'RETIRED'}[action]);await page.screenshot({path:path.join(c.evidence,'resource-lifecycle-'+action+'.png'),fullPage:true,animations:'disabled'});await box.getByRole('button',{name:'关闭',exact:true}).click()
 }
 if(!c.action){
  await page.getByRole('tab',{name:/字段元数据/}).click();const edits=page.getByTestId('resource-field-list').getByRole('button',{name:'编辑',exact:true});for(const edit of await edits.all())assert.equal(await edit.isDisabled(),true)
  await page.getByRole('tab',{name:/版本历史/}).click();await page.getByText('资源退役',{exact:true}).first().waitFor();await page.getByText('真实浏览器生命周期验收',{exact:true}).first().waitFor()
  await page.screenshot({path:path.join(c.evidence,'resource-lifecycle-history.png'),fullPage:true,animations:'disabled'})
 }
 assert.deepEqual(errors,[]);assert.equal(await page.evaluate(()=>localStorage.length+sessionStorage.length),0);console.log('Resource lifecycle browser passed: real state/version/history, impact and write failures, retained reason, stale conflict, retired read-only and safe DOM. Mode: '+(c.action||'complete'))
}catch(e){await page?.screenshot({path:path.join(c.evidence,'resource-lifecycle-error.png'),fullPage:true}).catch(()=>{});await writeFile(path.join(c.evidence,'resource-lifecycle-browser-error.json'),JSON.stringify({message:e.message,errors}));throw e}
finally{release?.();await browser?.close();await server.close()}
