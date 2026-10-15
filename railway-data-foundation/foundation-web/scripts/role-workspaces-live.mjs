import assert from 'node:assert/strict'
import { createServer } from 'vite'
import { chromium } from 'playwright-core'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
const foundation=process.env.ROLE_FOUNDATION_URL,password=process.env.ROLE_PASSWORD
assert.ok(['127.0.0.1','localhost'].includes(new URL(foundation).hostname))
assert.ok(password)
process.env.VITE_USE_MOCK='false'
const server=await createServer({cacheDir:'../../.runtime/role-vite-cache',server:{host:'127.0.0.1',port:5189,strictPort:true,proxy:{'/api':{target:foundation,changeOrigin:true}}}})
let browser
const contexts=[],errors=[];let demandPage, providerPage
try{
 await mkdir('../../.runtime/role-evidence',{recursive:true});await server.listen();const base='http://127.0.0.1:'+server.httpServer.address().port
 browser=await chromium.launch({executablePath:process.env.FOUNDATION_BROWSER||'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
 async function enter(username){const context=await browser.newContext({viewport:{width:1550,height:1000},locale:'zh-CN'});contexts.push(context);const page=await context.newPage();page.setDefaultTimeout(30000);page.on('pageerror',e=>errors.push(e.message));await page.goto(base+'/login',{waitUntil:'domcontentloaded'});await page.getByRole('textbox',{name:'登录账号',exact:true}).fill(username);await page.getByRole('textbox',{name:'登录密码',exact:true}).fill(password);await page.getByRole('button',{name:'进入平台',exact:true}).click();await page.waitForURL('**/dashboard');await page.waitForLoadState('networkidle');return page}
 async function data(page,method,url,body,status=200){const token=(await (await page.request.get(base+'/api/v1/auth/csrf')).json()).data;const response=await page.request.fetch(base+'/api/v1'+url,{method,data:body,headers:{[token.header_name]:token.token}});assert.equal(response.status(),status,method+' '+url);const envelope=await response.json();if(status===200)assert.equal(envelope.code,0);return envelope.data}
 const admin=await enter(process.env.ROLE_ADMIN),provider=await enter(process.env.ROLE_PROVIDER),demand=await enter(process.env.ROLE_DEMAND);demandPage=demand;providerPage=provider
 assert.equal(await provider.getByRole('menuitem',{name:'用户账号',exact:true}).count(),0)
 assert.equal(await demand.getByRole('menuitem',{name:'我的数据源',exact:true}).count(),0)
 await admin.goto(base+'/accounts',{waitUntil:'networkidle'});await admin.getByRole('heading',{name:'用户账号',exact:true}).waitFor()
 // Account administration uses actual backend pages and writes, with a deliberate read failure only.
 const accountPrefix=process.env.ROLE_ACCOUNT_PREFIX, newUser=accountPrefix+'_created'
 const accountTable=admin.getByTestId('account-table'), searchBox=admin.getByRole('textbox',{name:'搜索账号',exact:true})
 await searchBox.fill(accountPrefix);await admin.getByRole('button',{name:'查询',exact:true}).click()
 await admin.getByText('共 30 条',{exact:true}).waitFor();assert.equal(await accountTable.locator('tbody tr').count(),20)
 await admin.locator('.el-pagination .btn-next').click();await admin.waitForFunction(()=>document.querySelector('[data-testid=account-table] tbody')?.rows.length===10);assert.equal(await accountTable.locator('tbody tr').count(),10)
 await admin.route('**/api/v1/accounts/catalog?**',route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:10001,message:'账号读取测试故障',data:null})}),{times:1})
 await admin.getByRole('button',{name:'刷新账号',exact:true}).click();await admin.getByTestId('account-error').getByText('账号读取测试故障',{exact:true}).waitFor()
 assert.equal(await accountTable.locator('tbody tr').count(),0)
 await admin.getByRole('button',{name:'重试读取',exact:true}).click();await admin.waitForFunction(()=>document.querySelector('[data-testid=account-table] tbody')?.rows.length===10);await admin.getByText('共 30 条',{exact:true}).waitFor();assert.equal(await accountTable.locator('tbody tr').count(),10)
 await admin.getByRole('button',{name:'创建账号',exact:true}).click();const create=admin.getByRole('dialog',{name:'创建用户账号',exact:true})
 await create.getByRole('button',{name:'保存账号',exact:true}).click();await create.getByText(/登录账号须为3至64/).waitFor()
 await create.getByLabel('登录账号',{exact:true}).fill(newUser);await create.getByLabel('用户名称',{exact:true}).fill('新建工作台验收用户')
 await create.locator('.el-form-item').filter({hasText:'关联共享主体'}).locator('.el-select').click()
 await create.locator('.el-form-item').filter({hasText:'关联共享主体'}).locator('input').fill(process.env.ROLE_OPTION_PREFIX)
 await create.getByRole('button',{name:/加载更多主体/}).waitFor();await create.getByRole('button',{name:/加载更多主体/}).click()
 await create.getByRole('option',{name:process.env.ROLE_OPTION_PREFIX+'20',exact:false}).click()
 await create.getByLabel('初始密码',{exact:true}).fill(password);await create.getByRole('button',{name:'保存账号',exact:true}).click();await create.waitFor({state:'hidden'})
 await searchBox.fill(newUser);await admin.getByRole('button',{name:'查询',exact:true}).click();await admin.getByText('共 1 条',{exact:true}).waitFor()
 const createdRow=accountTable.getByRole('row').filter({hasText:newUser});await createdRow.waitFor()
 assert.equal(await admin.evaluate(p=>[localStorage,sessionStorage].some(s=>Object.values(s).some(v=>v.includes(p))),password),false)
 await createdRow.getByRole('button',{name:'重置密码',exact:true}).click();const reset=admin.getByRole('dialog',{name:'重置密码',exact:true})
 await reset.getByLabel('新密码',{exact:true}).fill(password+'_abandoned');await reset.getByRole('button',{name:'取消',exact:true}).click();await reset.waitFor({state:'hidden'})
 await createdRow.getByRole('button',{name:'重置密码',exact:true}).click();assert.equal(await reset.getByLabel('新密码',{exact:true}).inputValue(),'')
 const current=(await data(admin,'GET','/accounts/catalog?keyword='+newUser)).items[0]
 await data(admin,'POST',`/accounts/${current.user_id}/status`,{version:current.version,enabled:false})
 await reset.getByLabel('新密码',{exact:true}).fill(password+'_conflict');await reset.getByRole('button',{name:'保存新密码',exact:true}).click()
 await reset.getByText('账号已变化，请刷新',{exact:true}).waitFor();assert.equal(await reset.getByLabel('新密码',{exact:true}).inputValue(),'')
 await reset.getByRole('button',{name:'关闭并刷新',exact:true}).click();await reset.waitFor({state:'hidden'});await createdRow.getByText('停用',{exact:true}).first().waitFor()
 await createdRow.getByRole('button',{name:'启用',exact:true}).click();await admin.getByRole('dialog',{name:'账号状态',exact:true}).getByRole('button',{name:'确认',exact:true}).click();await createdRow.getByText('启用',{exact:true}).first().waitFor()
 const duplicate=await enter(newUser)
 await createdRow.getByRole('button',{name:'重置密码',exact:true}).click();await reset.getByLabel('新密码',{exact:true}).fill(password+'_reset');await reset.getByRole('button',{name:'保存新密码',exact:true}).click();await reset.waitFor({state:'hidden'})
 await duplicate.reload({waitUntil:'networkidle'});await duplicate.waitForURL('**/login')
 await duplicate.getByRole('textbox',{name:'登录账号',exact:true}).fill(newUser);await duplicate.getByRole('textbox',{name:'登录密码',exact:true}).fill(password+'_reset');await duplicate.getByRole('button',{name:'进入平台',exact:true}).click();await duplicate.waitForURL('**/dashboard')
 await admin.screenshot({path:'../../.runtime/role-evidence/accounts.png',fullPage:true})
 await provider.goto(base+'/sources',{waitUntil:'networkidle'});const row=provider.getByTestId('source-table').getByRole('row').filter({hasText:'提供方甲测试数据源'});await row.waitFor();assert.equal(await provider.getByTestId('source-table').getByRole('row').filter({hasText:'提供方乙'}).count(),0)
 await row.getByRole('button',{name:'开始扫描',exact:true}).click();await provider.waitForURL('**/scans**');await provider.getByText('container_event',{exact:true}).first().waitFor()
 await demand.goto(base+'/resources/'+process.env.ROLE_RESOURCE,{waitUntil:'networkidle'});await demand.getByRole('heading',{name:'公开资源甲',exact:true}).waitFor();await demand.getByRole('tab',{name:'可申请接口能力'}).click()
 const rest=demand.getByRole('row').filter({hasText:'能力REST_API'});await rest.getByRole('button',{name:'申请共享',exact:true}).click();await demand.getByRole('dialog',{name:'新增共享关系',exact:true}).waitFor()
 const dialog=demand.getByRole('dialog',{name:'新增共享关系',exact:true});await dialog.getByLabel('关系名称',{exact:true}).fill('真实浏览器共享申请');await dialog.getByLabel('使用目的',{exact:true}).fill('多角色流程验收');await dialog.getByLabel('操作原因',{exact:true}).fill('独立测试登记');await dialog.locator('.el-checkbox').first().click();await dialog.locator('.el-form-item').filter({hasText:'时间条件字段'}).locator('.el-select').click();await demand.getByRole('option',{name:'event_time',exact:true}).click();await dialog.getByRole('button',{name:'保存共享关系',exact:true}).click();await dialog.waitFor({state:'hidden'})
 const relationshipRow=demand.getByTestId('relationship-table').getByRole('row').filter({hasText:'真实浏览器共享申请'});await relationshipRow.waitFor();await relationshipRow.getByRole('button',{name:'状态操作',exact:true}).click();await demand.getByRole('menuitem',{name:'提交确认',exact:true}).click();await demand.getByRole('textbox',{name:'状态操作原因',exact:true}).fill('需求方提交');await demand.getByRole('dialog').getByRole('button',{name:'确认操作',exact:true}).click();await relationshipRow.getByText('待确认',{exact:true}).waitFor()
 assert.equal(await demand.getByRole('menuitem',{name:'确认通过',exact:true}).count(),0)
 let rel=(await data(demand,'GET','/relationships')).find(r=>r.relationship_name==='真实浏览器共享申请')
 await data(admin,'POST',`/relationships/${rel.relationship_id}/confirm`,{version:rel.version,reason:'提供方未确认不能审批'},400)
 await provider.goto(base+'/relationships',{waitUntil:'networkidle'})
 const providerRest=provider.getByTestId('relationship-table').getByRole('row').filter({hasText:'真实浏览器共享申请'})
 await providerRest.getByRole('button',{name:'内容确认',exact:true}).click();await provider.getByRole('menuitem',{name:'退回申请',exact:true}).click()
 const returned=provider.getByRole('dialog',{name:'退回申请',exact:true});await returned.getByRole('textbox',{name:'状态操作原因',exact:true}).fill('提供方要求重新核对范围');await returned.getByRole('button',{name:'确认操作'}).click();await returned.waitFor({state:'hidden'});await providerRest.getByText('草稿',{exact:true}).waitFor()
 await demand.getByRole('button',{name:'刷新列表',exact:true}).click();await relationshipRow.getByText('草稿',{exact:true}).waitFor();await relationshipRow.getByRole('button',{name:'状态操作',exact:true}).click();await demand.getByRole('menuitem',{name:'提交确认',exact:true}).click();await demand.getByRole('textbox',{name:'状态操作原因',exact:true}).fill('需求方重新提交');await demand.getByRole('dialog').getByRole('button',{name:'确认操作',exact:true}).click();await relationshipRow.getByText('待确认',{exact:true}).waitFor()
 await provider.getByRole('button',{name:'刷新列表',exact:true}).click();await providerRest.getByRole('button',{name:'内容确认',exact:true}).click();await provider.getByRole('menuitem',{name:'确认共享内容',exact:true}).click()
 const restReview=provider.getByRole('dialog',{name:'确认共享内容',exact:true});await restReview.getByRole('textbox',{name:'状态操作原因',exact:true}).fill('提供方已核对字段与时间范围');await restReview.getByRole('button',{name:'确认操作',exact:true}).click();await restReview.getByTestId('relationship-action-error').getByText('请先核对并确认共享内容',{exact:true}).waitFor()
 await restReview.locator('.el-checkbox').click();await provider.route('**/relationships/*/provider-confirm',route=>route.abort('failed'),{times:1});await restReview.getByRole('button',{name:'确认操作',exact:true}).click();await restReview.getByTestId('relationship-action-error').getByText(/未确认|失败/).waitFor();assert.equal((await data(demand,'GET','/relationships/'+rel.relationship_id)).provider_confirmed_at,null)
 await provider.screenshot({path:'../../.runtime/role-evidence/provider-rest-review.png',fullPage:true,animations:'disabled'});await restReview.getByRole('button',{name:'确认操作',exact:true}).click();await restReview.waitFor({state:'hidden'});await providerRest.getByText('已确认',{exact:true}).waitFor()
 rel=await data(demand,'GET','/relationships/'+rel.relationship_id);await data(admin,'POST',`/relationships/${rel.relationship_id}/confirm`,{version:rel.version,reason:'真实管理员最终审批'})
 await provider.goto(base+'/relationships',{waitUntil:'networkidle'});await provider.getByTestId('relationship-table').getByRole('row').filter({hasText:'真实浏览器共享申请'}).waitFor();assert.equal(await provider.getByRole('button',{name:'新增共享关系',exact:true}).count(),0);assert.equal(await provider.getByRole('button',{name:'状态操作',exact:true}).count(),0)
 // Actual authenticated catalogs: changing provider clears dependent choices and scope acknowledgement.
 await admin.goto(base+'/kafka-subscriptions',{waitUntil:'networkidle'});await admin.getByRole('button',{name:'新增订阅申请',exact:true}).click()
 const adminKafka=admin.getByRole('dialog',{name:'新增订阅申请',exact:true})
 async function chooseKafka(page,dialog,label,value){await dialog.locator('.el-select').filter({has:page.getByRole('combobox',{name:label,exact:true})}).click();await page.getByRole('option',{name:value,exact:true}).click()}
 await chooseKafka(admin,adminKafka,'提供方','提供方甲');await chooseKafka(admin,adminKafka,'数据资源','公开资源甲');await chooseKafka(admin,adminKafka,'Kafka 能力','能力KAFKA_TOPIC');await adminKafka.locator('.el-checkbox').click()
 await chooseKafka(admin,adminKafka,'提供方','提供方乙');assert.equal(await adminKafka.getByRole('checkbox').isChecked(),false);assert.equal(await adminKafka.getByRole('combobox',{name:'Kafka 能力',exact:true}).isDisabled(),true)
 await adminKafka.locator('.el-select').filter({has:admin.getByRole('combobox',{name:'数据资源',exact:true})}).click()
 assert.equal(await admin.getByRole('option',{name:'公开资源甲',exact:true}).count(),0);await admin.getByRole('option',{name:'内部资源乙',exact:true}).click();await chooseKafka(admin,adminKafka,'Kafka 能力','能力KAFKA_TOPIC');await admin.screenshot({path:'../../.runtime/role-evidence/kafka-provider-selection.png',fullPage:true,animations:'disabled'});await adminKafka.getByRole('button',{name:'取消',exact:true}).click()
 // A stale or mismatched catalog link must not open a plausible successful form.
 await demand.goto(base+'/kafka-subscriptions?resource='+process.env.ROLE_RESOURCE_B+'&capability='+process.env.ROLE_KAFKA,{waitUntil:'networkidle'});await demand.getByTestId('subscription-entry-error').waitFor();assert.equal(await demand.getByRole('dialog',{name:'新增订阅申请'}).count(),0)
 let heldOptions,releaseOptions,finishOptions
 const optionsDone=new Promise(done=>{finishOptions=done})
 await demand.route('**/api/v1/subjects?**',async route=>{if(!heldOptions){heldOptions=true;try{const response=await route.fetch();await new Promise(done=>{releaseOptions=done});await route.fulfill({response})}finally{finishOptions()}}else await route.continue()})
 await demand.getByTestId('subscription-entry-error').getByRole('button',{name:'重试申请入口'}).click()
 for(let attempt=0;attempt<100&&!releaseOptions;attempt++)await new Promise(done=>setTimeout(done,20))
 assert.equal(typeof releaseOptions,'function')
 // Vue reuses the same route component; the late first read must not overwrite the new resource.
 await demand.evaluate(query=>{history.pushState({},'',query);window.dispatchEvent(new PopStateEvent('popstate'))},'/kafka-subscriptions?resource='+process.env.ROLE_RESOURCE+'&capability='+process.env.ROLE_KAFKA)
 const kafka=demand.getByRole('dialog',{name:'新增订阅申请',exact:true});await kafka.waitFor();releaseOptions();await optionsDone;await demand.unroute('**/api/v1/subjects?**')
 await kafka.getByText('公开资源甲',{exact:true}).waitFor();assert.equal(await demand.getByTestId('subscription-entry-error').count(),0)
 await kafka.getByRole('button',{name:'取消',exact:true}).click()
 // Reference failures are visible, do not use demo data, and can be retried.
 await demand.route('**/api/v1/capabilities?**',route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:10001,message:'登记选项读取故障',data:null})}),{times:1})
 await demand.getByRole('button',{name:'刷新',exact:true}).click();await demand.getByTestId('subscription-reference-error').getByText('登记选项读取故障',{exact:true}).waitFor();assert.equal(await demand.getByRole('button',{name:'新增订阅申请',exact:true}).isDisabled(),true)
 await demand.getByTestId('subscription-reference-error').getByRole('button',{name:'重试登记选项'}).click();await kafka.waitFor()
 await kafka.getByRole('textbox',{name:'授权名称',exact:true}).fill('真实浏览器Kafka申请');await kafka.getByRole('textbox',{name:'使用目的',exact:true}).fill('整主题独立审批');await kafka.getByRole('textbox',{name:'操作原因',exact:true}).fill('测试登记');await kafka.locator('.el-checkbox').click();await demand.locator('.el-message--error').waitFor({state:'hidden'});await demand.screenshot({path:'../../.runtime/role-evidence/kafka-demand-application.png',fullPage:true,animations:'disabled'});await kafka.getByRole('button',{name:'保存申请',exact:true}).click();await kafka.waitFor({state:'hidden'});await demand.getByTestId('subscription-table').getByRole('row').filter({hasText:'真实浏览器Kafka申请'}).waitFor()
 await provider.goto(base+'/kafka-subscriptions',{waitUntil:'networkidle'});await provider.getByRole('heading',{name:'Kafka 订阅记录',exact:true}).waitFor();await provider.getByTestId('subscription-table').getByText('真实浏览器Kafka申请',{exact:true}).waitFor();assert.equal(await provider.getByRole('button',{name:'新增订阅申请',exact:true}).count(),0);assert.equal(await provider.getByRole('button',{name:/审批操作|订阅操作/}).count(),0)
 await demand.goto(base+'/kafka-subscriptions',{waitUntil:'networkidle'});const demandGrant=demand.getByTestId('subscription-table').getByRole('row').filter({hasText:'真实浏览器Kafka申请'});await demandGrant.getByRole('button',{name:'审批操作',exact:true}).click();await demand.getByRole('menuitem',{name:'提交确认',exact:true}).click();await demand.getByRole('textbox',{name:'审批操作原因',exact:true}).fill('需求方提交整主题申请');await demand.getByRole('dialog',{name:'提交确认',exact:true}).getByRole('button',{name:'确认操作',exact:true}).click();await demandGrant.getByText('待提供方确认',{exact:true}).waitFor()
 await provider.getByRole('button',{name:'刷新',exact:true}).click();const providerGrant=provider.getByTestId('subscription-table').getByRole('row').filter({hasText:'真实浏览器Kafka申请'});await providerGrant.getByRole('button',{name:'内容确认',exact:true}).click();await provider.getByRole('menuitem',{name:'确认共享内容',exact:true}).click()
 const kafkaReview=provider.getByRole('dialog',{name:'确认共享内容',exact:true});await kafkaReview.getByText('整主题独立审批',{exact:true}).waitFor();await kafkaReview.getByRole('textbox',{name:'审批操作原因',exact:true}).fill('提供方确认完整消息、历史与有效期');await kafkaReview.locator('.el-checkbox').click();await provider.screenshot({path:'../../.runtime/role-evidence/provider-kafka-review.png',fullPage:true,animations:'disabled'});await kafkaReview.getByRole('button',{name:'确认操作',exact:true}).click();await kafkaReview.waitFor({state:'hidden'});await providerGrant.getByText('已确认',{exact:true}).waitFor()
 await admin.goto(base+'/kafka-subscriptions',{waitUntil:'networkidle'});const adminGrant=admin.getByTestId('subscription-table').getByRole('row').filter({hasText:'真实浏览器Kafka申请'});await adminGrant.getByRole('button',{name:'审批操作',exact:true}).click();await admin.getByRole('menuitem',{name:'确认整主题范围',exact:true}).click();const finalReview=admin.getByRole('dialog',{name:'确认整主题范围',exact:true});await finalReview.getByRole('textbox',{name:'审批操作原因',exact:true}).fill('管理员最终审批');await finalReview.locator('.el-checkbox').click();await finalReview.getByRole('button',{name:'确认操作',exact:true}).click();await finalReview.waitFor({state:'hidden'});const finalGrant=(await data(admin,'GET','/kafka-subscriptions?keyword='+encodeURIComponent('真实浏览器Kafka申请'))).items[0];assert.equal(finalGrant.status,'APPROVED');assert.ok(finalGrant.provider_confirmed_at);assert.equal(finalGrant.execution_state,'NOT_PROVISIONED');await admin.screenshot({path:'../../.runtime/role-evidence/provider-kafka-final-review.png',fullPage:true,animations:'disabled'})
 await demand.goto(base+'/resources/'+process.env.ROLE_RESOURCE,{waitUntil:'networkidle'});await demand.getByRole('tab',{name:'可申请接口能力',exact:true}).click();await demand.getByText('Kafka 消息主题',{exact:true}).first().waitFor();const unsupported=demand.getByRole('row').filter({hasText:'无独立认证主题'});assert.equal(await unsupported.getByRole('button',{name:'申请订阅'}).count(),0);await unsupported.getByText('暂不可申请',{exact:true}).waitFor()
 await demand.screenshot({path:'../../.runtime/role-evidence/kafka-public-catalog.png',fullPage:true,animations:'disabled'})
 await demand.goto(base+'/credentials',{waitUntil:'networkidle'});await demand.getByRole('button',{name:'签发调用凭据',exact:true}).click();const cred=demand.getByRole('dialog',{name:'签发调用凭据',exact:true});await cred.getByLabel('凭据名称',{exact:true}).fill('真实浏览器凭据');await cred.getByLabel('签发原因',{exact:true}).fill('本主体领取');await cred.getByRole('button',{name:'确认签发',exact:true}).click();const tokenInput=cred.getByRole('textbox',{name:'本次调用令牌',exact:true});await tokenInput.waitFor();const token=await tokenInput.inputValue();assert.ok(token.startsWith('rdf_'));assert.equal(await demand.evaluate(p=>[localStorage,sessionStorage].some(s=>Object.values(s).some(v=>v.includes(p))),token),false)
 // Authenticated user changes invalidate all sensitive in-memory views.
 const identity=await data(demand,'GET','/auth/me');await data(admin,'POST',`/accounts/${identity.user_id}/status`,{version:identity.version,enabled:false});await demand.getByRole('button',{name:'已保存，关闭',exact:true}).click();await demand.getByRole('button',{name:'刷新凭据',exact:true}).click();await demand.waitForURL('**/login');assert.equal(await demand.getByTestId('issued-token').count(),0)
 await mkdir('../../.runtime/role-evidence',{recursive:true});await provider.screenshot({path:'../../.runtime/role-evidence/provider.png',fullPage:true});await admin.screenshot({path:'../../.runtime/role-evidence/admin.png',fullPage:true});assert.deepEqual(errors,[])
 // Expired Cookie during logout must also clear identity, rather than leaving an old workspace.
 await provider.context().clearCookies();await provider.getByRole('button',{name:'退出',exact:true}).click();await provider.waitForURL('**/login');assert.equal(await provider.getByRole('menuitem',{name:'我的数据源',exact:true}).count(),0);assert.deepEqual(errors,[])
 const oldAdmin=await enter(process.env.ROLE_ADMIN),nextPassword=password+'_new'
 await admin.getByRole('button',{name:'修改密码',exact:true}).click()
 const passwordDialog=admin.getByRole('dialog',{name:'修改登录密码',exact:true})
 await passwordDialog.getByLabel('当前密码',{exact:true}).fill('incorrect')
 await passwordDialog.getByLabel('新密码',{exact:true}).fill(nextPassword)
 await passwordDialog.getByLabel('确认新密码',{exact:true}).fill(nextPassword)
 await passwordDialog.getByRole('button',{name:'保存新密码',exact:true}).click()
 await passwordDialog.getByText('当前密码不正确',{exact:true}).waitFor()
 assert.equal(await passwordDialog.getByLabel('当前密码',{exact:true}).inputValue(),'')
 await passwordDialog.getByRole('button',{name:'取消',exact:true}).click();await passwordDialog.waitFor({state:'hidden'})
 await admin.getByRole('button',{name:'修改密码',exact:true}).click();assert.equal(await passwordDialog.getByLabel('新密码',{exact:true}).inputValue(),'')
 await admin.screenshot({path:'../../.runtime/role-evidence/password-dialog.png',animations:'disabled',mask:[passwordDialog.locator('input[type=password]')],maskColor:'#dfe8ef'})
 await passwordDialog.getByLabel('当前密码',{exact:true}).fill(password);await passwordDialog.getByLabel('新密码',{exact:true}).fill(nextPassword);await passwordDialog.getByLabel('确认新密码',{exact:true}).fill(nextPassword)
 assert.equal(await admin.evaluate(p=>[localStorage,sessionStorage].some(s=>Object.values(s).some(v=>v.includes(p))),nextPassword),false)
 await passwordDialog.getByRole('button',{name:'保存新密码',exact:true}).click();await admin.waitForURL('**/login');assert.equal(await admin.getByRole('dialog').count(),0)
 await oldAdmin.reload({waitUntil:'networkidle'});await oldAdmin.waitForURL('**/login')
 await admin.getByRole('textbox',{name:'登录账号',exact:true}).fill(process.env.ROLE_ADMIN);await admin.getByRole('textbox',{name:'登录密码',exact:true}).fill(nextPassword);await admin.getByRole('button',{name:'进入平台',exact:true}).click();await admin.waitForURL('**/dashboard');assert.deepEqual(errors,[])
 console.log('真实浏览器三角色通过：REST/Kafka需求方提交、提供方确认与退回、管理员最终审批；确认失败无伪成功、账号及目录回归。')
}catch(error){console.error('Browser page errors:',JSON.stringify(errors));await providerPage?.screenshot({path:'../../.runtime/role-evidence/provider-error.png',fullPage:true,mask:[providerPage.locator('input[type=password]')]}).catch(()=>{});await mkdir('../../.runtime/role-evidence',{recursive:true});await demandPage?.screenshot({path:'../../.runtime/role-evidence/error.png',fullPage:true,mask:[demandPage.locator('input[type=password], [data-testid=issued-token], [data-testid=subscription-secret]')]}).catch(()=>{});throw error}
finally{for(const context of contexts)await context.close();await browser?.close();await server.close()}
