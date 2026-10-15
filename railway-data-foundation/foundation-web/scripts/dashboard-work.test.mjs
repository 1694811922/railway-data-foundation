import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dashboardWork, filterDashboardWork } from '../src/utils/dashboardWork.js'
import { catalogPage } from '../src/utils/resourceCatalog.js'

const dataset=rows=>({rows,complete:true,error:false})
test('work summaries include only draft resources/capabilities and failed scans',()=>{
  const data={resources:dataset([{resource_id:'R',status:'DRAFT'},{resource_id:'P',status:'PUBLISHED'}]),
    capabilities:dataset([{capability_id:'C',status:'DRAFT'},{capability_id:'V',status:'VERIFIED'},{capability_id:'D',status:'DISABLED'}]),
    tasks:dataset(['FAILED','SUCCESS','RUNNING','PENDING'].map((status,i)=>({task_id:'T'+i,status})))}
  for(const key of ['resources','capabilities','tasks'])assert.equal(dashboardWork(data,key).count,1)
  assert.equal(dashboardWork({},'resources').state,'loading')
})
test('independent failures, incomplete reads and foreign demo rows never become empty success',()=>{
  const data={resources:{...dataset([]),error:true},tasks:{...dataset([{task_id:'T',status:'FAILED'}]),complete:false,notice:'读取受限'}}
  assert.equal(dashboardWork(data,'resources').count,null)
  assert.equal(dashboardWork(data,'tasks').partial,true)
  assert.equal(dashboardWork(data,'tasks').notice,'读取受限')
  for(const rows of [null,[{}],[{resource_id:'R',status:'DRAFT',_demo:true}]])
    assert.equal(dashboardWork({resources:dataset(rows)},'resources').state,'error')
  assert.equal(dashboardWork({resources:dataset([{resource_id:'R',status:'DRAFT',_demo:true}])},'resources',true).count,1)
})
test('work actions use existing read-only destinations and cannot imply CDC verification support',()=>{
  const data={resources:dataset([{resource_id:'R/#',status:'DRAFT'}]),
    capabilities:dataset([{capability_id:'C',resource_id:'R',type:'CDC_STREAM',status:'DRAFT'},
      {capability_id:'REST',resource_id:'R',type:'REST_API',http_method:'GET',endpoint:'/containers/events',auth_type:'NONE',status:'DRAFT'},
      {capability_id:'ORPHAN',status:'DRAFT'}]),tasks:dataset([{task_id:'T',source_name:'港口来源',status:'FAILED',failure_reason:'权限不足'}])}
  assert.equal(dashboardWork(data,'resources').rows[0].route.path,'/resources/R%2F%23')
  const rows=dashboardWork(data,'capabilities').rows
  assert.match(rows.find(r=>r.id==='C').reason,/当前只支持/)
  assert.deepEqual(rows.find(r=>r.id==='C').route,{path:'/capabilities',query:{resource:'R'}})
  assert.match(rows.find(r=>r.id==='REST').reason,/主动验证/)
  assert.equal(rows.find(r=>r.id==='ORPHAN').route,null)
  assert.deepEqual(dashboardWork(data,'tasks').rows[0].route,{path:'/scans',query:{task:'T'}})
})
test('work searches combine keywords, sort recent records first and clamp pagination after changes',()=>{
  const data={resources:dataset(Array.from({length:25},(_,i)=>({resource_id:'R'+i,resource_name:'港口草稿 '+i,source_name:'铁路来源',status:'DRAFT',updated_at:i===24?'2026-10-04T09:00:00':'2026-10-03T09:00:00'})))}
  const group=dashboardWork(data,'resources'), snapshot=JSON.stringify(data)
  assert.equal(group.rows[0].id,'R24')
  assert.equal(filterDashboardWork(group.rows,'R24 铁路')[0].id,'R24')
  assert.equal(filterDashboardWork(group.rows,'R24 不存在').length,0)
  assert.equal(catalogPage(group.rows,3,10).rows.length,5)
  assert.equal(catalogPage(filterDashboardWork(group.rows,'R24'),3,10).page,1)
  assert.equal(JSON.stringify(data),snapshot)
})
