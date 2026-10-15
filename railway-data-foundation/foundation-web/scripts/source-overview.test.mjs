import { test } from 'node:test'
import assert from 'node:assert/strict'
import { sourceOverviewInfo,sourceOverviewRows,readSourceOverview } from '../src/utils/sourceOverview.js'
const source={source_id:'S'}

test('source overview displays explicit basic metadata without credential references or secrets',()=>{
  const shown=sourceOverviewInfo({...source,source_name:'港口',username:'reader',password:'not-for-display',credential_ref:'private-reference',connection_env_prefix:'PRIVATE',future_secret:'secret'})
  assert.equal(shown.username,'reader')
  assert.equal(shown.source_id,'S')
  for(const key of ['password','credential_ref','connection_env_prefix','future_secret'])assert.equal(Object.hasOwn(shown,key),false)
})
test('source overview filters by exact source and orders resources by creation rather than modification',()=>{
  const rows=[{resource_id:'R1',source_id:'S',created_at:'2026-10-02T00:00:00',updated_at:'2026-10-04T00:00:00'},
    {resource_id:'R2',source_id:'S',created_at:'2026-10-03T00:00:00'}, {resource_id:'OTHER',source_id:'S2',created_at:'2026-10-04T00:00:00'}]
  assert.deepEqual(sourceOverviewRows({rows},source,'resources',false).map(row=>row.resource_id),['R2','R1'])
  assert.equal(rows[0].resource_id,'R1')
})
test('malformed, duplicate and mixed-mode associated records cannot masquerade as valid overview',()=>{
  for(const rows of [null,[{}],[{task_id:'T'}],[{task_id:'T',source_id:'S',_demo:true}],
    [{task_id:'T',source_id:'S'},{task_id:'T',source_id:'S'}]])
    assert.throws(()=>sourceOverviewRows({rows},source,'tasks',false))
  assert.throws(()=>sourceOverviewRows({rows:[]},{...source,_demo:true},'tasks',false))
})
test('overview reads across default pages, labels global bound and isolates failing sections',async()=>{
  const rows=Array.from({length:451},(_,i)=>({resource_id:'R'+i,source_id:i===450?'S':'OTHER'}))
  const read=await readSourceOverview('resources',source,async({offset,limit})=>rows.slice(offset,offset+limit))
  assert.equal(read.rows.length,1);assert.equal(read.rows[0].resource_id,'R450');assert.equal(read.complete,true)
  const bounded=await readSourceOverview('resources',source,async({offset,limit})=>rows.slice(offset,offset+limit),{maxRows:400})
  assert.equal(bounded.rows.length,0);assert.equal(bounded.complete,false)
  const failed=await readSourceOverview('tasks',source,async()=>{throw new Error('offline')},{getDemoRows:()=>{throw new Error('must not fallback')}})
  assert.equal(failed.error,true);assert.deepEqual(failed.rows,[]);assert.equal(read.error,false)
})
test('explicit demo source reads only browser metadata without invoking real APIs',async()=>{
  const demoSource={source_id:'S',_demo:true}, tasks=[{task_id:'T',source_id:'S',_demo:true},{task_id:'OTHER',source_id:'S2',_demo:true}]
  const result=await readSourceOverview('tasks',demoSource,async()=>{throw new Error('must not connect')},{getDemoRows:()=>tasks})
  assert.equal(result.error,false);assert.equal(result.demo,true);assert.equal(result.rows.length,1)
})
