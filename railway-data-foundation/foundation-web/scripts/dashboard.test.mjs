import { test } from 'node:test'
import assert from 'node:assert/strict'
import { fetchDashboardList, loadDashboard, dashboardMetrics, recentResources } from '../src/utils/dashboard.js'
import { beijingDay, displayTime } from '../src/utils/time.js'

const list = (key, length) => Array.from({length},(_,index)=>({ [key]:'ID_'+index }))
test('totals read beyond first API page and verify exact page boundaries', async()=>{
  const rows = list('resource_id',451), requests=[]
  const result = await fetchDashboardList(async params=>{ requests.push(params); return rows.slice(params.offset,params.offset+params.limit) },'resource_id')
  assert.equal(result.rows.length,451)
  assert.equal(result.complete,true)
  assert.deepEqual(requests.map(p=>p.offset),[0,200,400])
  const exact = await fetchDashboardList(async ({offset,limit})=>rows.slice(0,400).slice(offset,offset+limit),'resource_id')
  assert.equal(exact.rows.length,400)
  assert.equal(exact.complete,true)
})
test('bounded statistics and changing lists are labelled as incomplete',async()=>{
  for (const size of [4,5]) {
    const rows=list('source_id',size)
    const result=await fetchDashboardList(async ({offset,limit})=>rows.slice(offset,offset+limit),'source_id',{pageSize:2,maxRows:4})
    assert.equal(result.rows.length,4)
    assert.equal(result.complete,size===4)
  }
  const pages=[[{source_id:'A'},{source_id:'B'}],[{source_id:'B'},{source_id:'C'}]]
  const result=await fetchDashboardList(async()=>pages.shift(),'source_id',{pageSize:2})
  assert.equal(result.complete,false)
  assert.equal(result.rows.length,2)
  assert.match(result.notice,/发生变化/)
})
test('failed or malformed pages never retain old or partial successful totals',async()=>{
  const requests={ sources:async()=>[], tasks:async()=>[], resources:async({offset})=>{
    if(offset) throw new Error('offline')
    return list('resource_id',200)
  }, capabilities:async()=>({unexpected:'response'}) }
  const data=await loadDashboard(requests)
  assert.equal(data.sources.complete,true)
  assert.equal(data.resources.error,true)
  assert.deepEqual(data.resources.rows,[])
  assert.equal(data.capabilities.error,true)
  const metrics=Object.fromEntries(dashboardMetrics(data).map(row=>[row.key,row.value]))
  assert.equal(metrics.sources,0)
  assert.equal(metrics.resources,null)
  assert.equal(metrics.capabilities,null)
  assert.equal(metrics.available,null)
})
test('today uses Beijing midnight consistently for UTC local datetimes and ISO offsets',()=>{
  assert.equal(beijingDay('2026-10-02T15:59:59'),'2026-10-02')
  assert.equal(beijingDay('2026-10-02T16:00:00'),'2026-10-03')
  assert.equal(beijingDay('2026-10-03T00:00:00+08:00'),'2026-10-03')
  assert.equal(beijingDay('2026-10-02 16:00:00'),'2026-10-03')
  assert.equal(beijingDay('invalid'),null)
  assert.equal(displayTime(null),'—')
  const data={ tasks:{rows:[{started_at:'2026-10-02T15:59:59'},{started_at:'2026-10-02T16:00:00'},{started_at:'invalid'}],complete:true} }
  assert.equal(dashboardMetrics(data,new Date('2026-10-02T20:00:00Z')).find(m=>m.key==='today').value,1)
})
test('available capabilities exclude disabled sources and unpublished resources',()=>{
  const data={ sources:{rows:[{source_id:'S1',status:'ENABLED'},{source_id:'S2',status:'DISABLED'}],complete:true},
    resources:{rows:[{resource_id:'R1',source_id:'S1',status:'PUBLISHED'},{resource_id:'R2',source_id:'S2',status:'PUBLISHED'},{resource_id:'R3',source_id:'S1',status:'DRAFT'}],complete:true},
    capabilities:{rows:['R1','R2','R3'].map(resource_id=>({resource_id,status:'ENABLED',health_status:'UP'})),complete:true} }
  assert.equal(dashboardMetrics(data).find(m=>m.key==='available').value,1)
  data.sources.complete=false
  assert.equal(dashboardMetrics(data).find(m=>m.key==='available').partial,true)
})
test('recent resources use creation time instead of last edit time',()=>{
  const rows=[{resource_id:'OLD',created_at:'2026-09-01T10:00:00',updated_at:'2026-10-03T10:00:00'},
    {resource_id:'NEW',created_at:'2026-10-02T10:00:00',updated_at:'2026-10-02T10:00:00'}]
  assert.equal(recentResources(rows)[0].resource_id,'NEW')
  assert.equal(rows[0].resource_id,'OLD')
})
