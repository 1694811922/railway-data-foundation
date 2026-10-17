import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import {createServer} from 'vite'
import {chromium} from 'playwright-core'
import net from 'node:net'
import path from 'node:path'
const c=JSON.parse(await readFile(process.argv[2],'utf8'))
for(const value of [c.foundation,c.provider])assert.ok(['127.0.0.1','localhost'].includes(new URL(value).hostname))
process.env.VITE_USE_MOCK='false'
const port=await new Promise(resolve=>{const s=net.createServer();s.listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>resolve(p))})})
const server=await createServer({cacheDir:path.join(c.evidence,'vite-governance-cache'),server:{host:'127.0.0.1',port,strictPort:true,proxy:{'/api':{target:c.foundation,changeOrigin:true},'/provider':{target:c.provider,changeOrigin:true,rewrite:p=>p.replace(/^\/provider/,'')}}}})
let browser,page;const errors=[]
try{
 await server.listen();browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
 page=await browser.newPage({viewport:{width:1680,height:1080},locale:'zh-CN'});page.setDefaultTimeout(20000);page.on('pageerror',e=>errors.push(e.message));const base=`http://127.0.0.1:${port}`
 await page.goto(base+'/login',{waitUntil:'networkidle'});await page.getByRole('textbox',{name:'登录账号',exact:true}).fill(process.env.GOVERNANCE_USER);await page.getByRole('textbox',{name:'登录密码',exact:true}).fill(process.env.GOVERNANCE_PASSWORD);await page.getByRole('button',{name:'进入平台',exact:true}).click();await page.waitForURL('**/dashboard')
 await page.getByRole('menuitem',{name:'共享治理',exact:true}).click();await page.getByRole('menuitem',{name:'共享授权',exact:true}).click();await page.getByRole('heading',{name:'共享授权',exact:true}).waitFor()
 for(const name of ['共享关系管理','访问策略','共享调用验证','Kafka 订阅授权'])assert.equal(await page.getByRole('menuitem',{name,exact:true}).count(),0)
 await page.getByRole('menuitem',{name:'系统管理',exact:true}).click();await page.getByRole('menuitem',{name:'用户账号',exact:true}).waitFor();await page.getByRole('menuitem',{name:'共享主体管理',exact:true}).waitFor()
 await page.getByRole('tab',{name:'Kafka订阅',exact:true}).click();await page.getByTestId('subscription-table').waitFor();assert.equal(new URL(page.url()).searchParams.get('method'),'KAFKA')
 await page.reload({waitUntil:'networkidle'});assert.equal(await page.getByRole('tab',{name:'Kafka订阅',exact:true}).getAttribute('aria-selected'),'true')
 await page.getByRole('tab',{name:'REST接口',exact:true}).click();await page.getByTestId('relationship-table').waitFor()
 await page.screenshot({path:path.join(c.evidence,'governance-unified-workspace.png'),fullPage:true,animations:'disabled'})
 const relation=(await (await page.request.get(base+'/api/v1/relationships/'+c.relationship_id)).json()).data
 const resource=(await (await page.request.get(base+'/api/v1/resources/'+relation.resource_id)).json()).data,rest=(await (await page.request.get(base+'/api/v1/capabilities/'+relation.capability_id)).json()).data,kafka=(await (await page.request.get(base+'/api/v1/capabilities?limit=200')).json()).data.find(row=>row.resource_id===relation.resource_id&&row.type==='KAFKA_TOPIC')
 await page.getByRole('button',{name:'新增共享关系',exact:true}).click();const create=page.getByRole('dialog',{name:'新增共享关系',exact:true})
 await create.locator('.el-form-item').filter({hasText:'数据资源'}).locator('.el-select').click();await page.getByRole('option',{name:resource.resource_name,exact:true}).click();await create.locator('.el-form-item').filter({hasText:'接口能力'}).locator('.el-select').click()
 assert.equal(await page.getByRole('option',{name:kafka.capability_name,exact:true}).count(),0);await page.getByRole('option',{name:rest.capability_name,exact:true}).click();await create.getByRole('button',{name:'取消',exact:true}).click()
 await page.goto(base+'/policies?policyId='+relation.policy.policy_id,{waitUntil:'networkidle'});assert.equal(new URL(page.url()).pathname,'/sharing-authorizations');const drawer=page.getByRole('dialog',{name:'接口共享授权详情',exact:true});await drawer.getByText(relation.policy.policy_id,{exact:true}).waitFor()
 await page.goto(base+'/access-check?relationshipId='+c.relationship_id,{waitUntil:'networkidle'})
 const modal=page.getByRole('dialog',{name:'接口共享调用测试',exact:true}),token=modal.getByLabel('需求方调用令牌',{exact:true});await token.waitFor()
 assert.equal(await modal.getByRole('combobox',{name:'选择共享关系',exact:true}).count(),0)
 await token.fill('rdf_'+'x'.repeat(43));let response=page.waitForResponse(r=>new URL(r.url()).pathname==='/containers/events'||new URL(r.url()).pathname==='/provider/containers/events');await modal.getByRole('button',{name:'发送共享查询',exact:true}).click();assert.equal((await response).status(),401);await modal.getByTestId('access-result').getByText(/HTTP 401/).waitFor();assert.equal((await page.request.get(base+'/api/v1/auth/me')).status(),200)
 await token.fill(process.env.GOVERNANCE_CALLER_TOKEN);response=page.waitForResponse(r=>new URL(r.url()).pathname==='/provider/containers/events');await modal.getByRole('button',{name:'发送共享查询',exact:true}).click();assert.equal((await response).status(),200);await modal.getByTestId('access-result-table').locator('tbody tr').first().waitFor()
 await page.screenshot({path:path.join(c.evidence,'governance-embedded-call-test.png'),fullPage:true,animations:'disabled'})
 await modal.locator('button.el-dialog__headerbtn').click();await modal.waitFor({state:'hidden'});await drawer.getByRole('button',{name:'调用测试',exact:true}).click();await token.waitFor();assert.equal(await token.inputValue(),'');assert.equal(await modal.getByTestId('access-result').count(),0)
 assert.equal(await page.evaluate(()=>localStorage.length+sessionStorage.length),0);assert.deepEqual(errors,[])
 console.log('Governance browser passed: consolidated menus, role-neutral legacy redirect, URL tabs, embedded policy and real authorized REST query, wrong token denial without login expiry, cleared temporary secrets.')
}catch(e){await page?.screenshot({path:path.join(c.evidence,'governance-browser-error.png'),fullPage:true}).catch(()=>{});throw e}finally{await browser?.close();await server.close()}
