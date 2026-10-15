import test from 'node:test'
import assert from 'node:assert/strict'
import {comparisonCandidates,comparisonResult,filterChanges,propertyValue} from '../src/utils/schemaComparison.js'
import {catalogPage} from '../src/utils/resourceCatalog.js'
import {demoStore,demoSeed} from '../src/mock/demoData.js'
import {demoComparison,demoComparisonCandidates} from '../src/mock/schemaComparisonData.js'
const resource={resource_id:'RES_A',source_id:'SRC_A',scan_task_id:'SCAN_A',schema_name:'test_db',physical_table:'events'}
const scan=(task_id)=>({task_id,source_id:'SRC_A',status:'SUCCESS',started_at:'2026-10-07T08:00:00Z',finished_at:'2026-10-07T08:01:00Z'})
const field={field_name:'code',data_type:'varchar',column_type:'varchar(10)',nullable:true,primary_key:false,default_value:null,technical_comment:'事件代码',ordinal_position:1}
const result=()=>({resource_id:'RES_A',resource_version:2,source_id:'SRC_A',schema_name:'test_db',physical_table:'events',
  baseline_scan:scan('SCAN_A'),target_scan:scan('SCAN_B'),comparison_status:'CHANGED',summary:{added:0,removed:0,modified:1,unchanged:3},table_changes:[],
  changes:[{change_type:'MODIFIED',field_name:'code',before:{...field},after:{...field,column_type:'varchar(40)'},changed_properties:['column_type']}]})
const wrap=(value,demo=false)=>({value,demo})
test('comparison candidate pagination rejects foreign, duplicate, baseline and mixed demo scans',()=>{
  assert.equal(comparisonCandidates(wrap({items:[scan('SCAN_B')],total:21}),resource,false).total,21)
  for(const value of [{items:[scan('SCAN_A')],total:1},{items:[scan('SCAN_B'),scan('SCAN_B')],total:2},
    {items:[{...scan('SCAN_B'),source_id:'other'}],total:1},{items:[{...scan('SCAN_B'),status:'FAILED'}],total:1},
    {items:[],total:-1},{items:Array.from({length:21},(_,i)=>scan('SCAN_'+i)),total:21}])
    assert.throws(()=>comparisonCandidates(wrap(value),resource,false))
  assert.throws(()=>comparisonCandidates(wrap({items:[],total:0},true),resource,false))
})
test('comparison binds exact source table baseline and target and requires a complete result',()=>{
  assert.equal(comparisonResult(wrap(result()),resource,'SCAN_B',false).resource_version,2)
  for(const patch of [{resource_id:'other'},{source_id:'other'},{schema_name:'other'},{physical_table:'other'},
    {resource_version:0},{baseline_scan:scan('other')},{target_scan:scan('other')},
    {target_scan:{...scan('SCAN_B'),started_at:'2025-01-01T00:00:00Z'}},{comparison_status:'SUCCESS'},{summary:{added:0,removed:0,modified:0,unchanged:3}}])
    assert.throws(()=>comparisonResult(wrap({...result(),...patch}),resource,'SCAN_B',false))
  assert.throws(()=>comparisonResult(wrap(result(),true),resource,'SCAN_B',false))
})
test('changed properties must exactly describe technical differences',()=>{
  for(const properties of [[],['primary_key'],['column_type','column_type'],['column_type','business_name']]){
    const value=result();value.changes[0].changed_properties=properties
    assert.throws(()=>comparisonResult(wrap(value),resource,'SCAN_B',false))
  }
  const value=result();value.changes.push(value.changes[0]);value.summary.modified=2
  assert.throws(()=>comparisonResult(wrap(value),resource,'SCAN_B',false))
})
test('unknown scope and missing table never render fabricated zero or all-deleted counts',()=>{
  for(const comparison_status of ['TABLE_NOT_FOUND','SCOPE_UNCONFIRMED']){
    const value={...result(),comparison_status,summary:null,changes:[]}
    assert.equal(comparisonResult(wrap(value),resource,'SCAN_B',false).summary,null)
    assert.throws(()=>comparisonResult(wrap({...value,summary:{added:0,removed:4,modified:0,unchanged:0}}),resource,'SCAN_B',false))
    assert.throws(()=>comparisonResult(wrap({...value,changes:result().changes}),resource,'SCAN_B',false))
  }
})
test('unchanged table and table-comment-only changes have distinct statuses',()=>{
  const value={...result(),changes:[],summary:{added:0,removed:0,modified:0,unchanged:4},comparison_status:'UNCHANGED'}
  comparisonResult(wrap(value),resource,'SCAN_B',false)
  const comment={property:'table_comment',before:'旧',after:'新'}
  comparisonResult(wrap({...value,comparison_status:'CHANGED',table_changes:[comment]}),resource,'SCAN_B',false)
  assert.throws(()=>comparisonResult(wrap({...value,table_changes:[comment]}),resource,'SCAN_B',false))
})
test('Chinese search and type filters reset pagination safely for 10/20/50 rows',()=>{
  const rows=Array.from({length:53},(_,i)=>({...result().changes[0],field_name:'code_'+i}))
  assert.equal(filterChanges(rows,{keyword:'CODE 完整列类型',type:'MODIFIED'}).length,53)
  assert.equal(filterChanges(rows,{keyword:'事件代码',type:'ADDED'}).length,0)
  for(const size of [10,20,50])assert.equal(catalogPage(rows,1,size).rows.length,size)
  assert.equal(catalogPage(filterChanges(rows,{keyword:'code_52'}),6,10).page,1)
})
test('null default, empty default and literal NULL stay distinguishable',()=>{
  assert.equal(propertyValue(field,'default_value'),'无默认值（NULL）')
  assert.equal(propertyValue({...field,default_value:''},'default_value'),'空字符串')
  assert.equal(propertyValue({...field,default_value:'NULL'},'default_value'),'NULL')
  assert.equal(propertyValue(null,'default_value'),'—')
})
test('explicit demo comparison matches wire contract and does not update resource fields or scans',()=>{
  const values=new Map();globalThis.localStorage={getItem:key=>values.get(key)??null,setItem:(key,value)=>values.set(key,value)}
  const demo=demoStore.list('resources')[0],before=JSON.stringify([...values])
  const candidates=demoComparisonCandidates(demo.resource_id)
  comparisonCandidates(wrap(candidates,true),demo,true)
  const value=demoComparison(demo.resource_id,candidates.items[0].task_id)
  comparisonResult(wrap(value,true),demo,candidates.items[0].task_id,true)
  assert.equal(JSON.stringify([...values]),before)
  assert.equal(value.summary.unchanged+value.summary.removed+value.summary.modified,demoSeed.fields.length)
  const legacy={...demo,scan_task_id:null};demoStore.put('resources',legacy,'resource_id')
  assert.throws(()=>demoComparisonCandidates(legacy.resource_id),/不可比较/)
  assert.throws(()=>demoComparisonCandidates('RES_real'),/不可比较/)
  delete globalThis.localStorage
})
