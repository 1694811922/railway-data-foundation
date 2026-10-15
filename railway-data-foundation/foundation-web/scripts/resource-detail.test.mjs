import test from 'node:test'
import assert from 'node:assert/strict'
import { detailRows, fieldChanges, fieldEditScope, filterResourceFields, validateFieldChanges } from '../src/utils/resourceDetail.js'
import { catalogPage } from '../src/utils/resourceCatalog.js'

test('resource fields combine multilingual search, primary/time marks and sensitivity',()=>{
  const rows=[{field_name:'event_time',business_name:'作业时间',data_type:'DATETIME',time_field:true,sensitivity_level:'内部'},
    {field_name:'id',data_type:'BIGINT',primary_key:true,sensitivity_level:'公开'},
    {field_name:'container_no',business_description:'箱号标识',technical_comment:'集装箱号',sensitivity_level:'敏感'}]
  assert.deepEqual(filterResourceFields(rows,{keyword:'TIME 作业',mark:'time',sensitivity_level:'内部'}),[rows[0]])
  assert.deepEqual(filterResourceFields(rows,{keyword:'big',mark:'primary'}),[rows[1]])
  assert.deepEqual(filterResourceFields(rows,{keyword:'箱号 标识',sensitivity_level:'敏感'}),[rows[2]])
  assert.deepEqual(filterResourceFields(rows,{mark:'primary',sensitivity_level:'内部'}),[])
})
test('detail rows reject malformed, foreign, duplicated and mixed-mode responses',()=>{
  const rows=[{resource_id:'RES_A',field_name:'id'}]
  assert.equal(detailRows({rows,demo:false},'RES_A','field_name',false),rows)
  assert.throws(()=>detailRows({rows,demo:true},'RES_A','field_name',false))
  for(const value of [null,[null],[{field_name:''}],[...rows,...rows],[{field_name:'id',resource_id:'RES_B'}]])
    assert.throws(()=>detailRows({rows:value,demo:false},'RES_A','field_name',false))
  assert.throws(()=>detailRows({rows:[{capability_id:'CAP_A',resource_id:'RES_B'}],demo:false},'RES_A','capability_id',false))
})
test('field writes whitelist business attributes and enforce current backend constraints',()=>{
  const form={field_name:'event_time',data_type:'datetime',business_name:'时间',business_description:'',time_field:true,sensitivity_level:'内部'}
  assert.deepEqual(fieldChanges(form,2),{business_name:'时间',business_description:'',time_field:true,sensitivity_level:'内部',resource_version:2})
  assert.equal(validateFieldChanges(form),'')
  for(const changes of [{business_name:'x'.repeat(121)},{business_description:'x'.repeat(1001)},{time_field:'true'},{sensitivity_level:'SECRET'}])
    assert.ok(validateFieldChanges({...form,...changes}))
})
test('field edit scope binds resource, version, field membership and data mode',()=>{
  const scope={resource_id:'RES_A',version:2,resource_version:2,field_name:'event_time',demo:false},item={resource_id:'RES_A',version:2},rows=[{field_name:'event_time',resource_version:2}]
  assert.equal(fieldEditScope(scope,item,rows,false),'')
  assert.ok(fieldEditScope(scope,{...item,resource_id:'RES_B'},rows,false))
  assert.ok(fieldEditScope(scope,{...item,version:3},rows,false))
  assert.ok(fieldEditScope(scope,item,[],false))
  assert.ok(fieldEditScope(scope,item,rows,true))
  assert.ok(fieldEditScope(null,item,rows,false))
  assert.ok(fieldEditScope({...scope,resource_version:undefined},item,rows,false))
  assert.ok(fieldEditScope(scope,item,[{...rows[0],resource_version:3}],false))
})
test('detail pagination clamps when filtering or retry shrinks metadata',()=>{
  const rows=Array.from({length:155},(_,i)=>({field_name:'field_'+i}))
  assert.equal(catalogPage(rows,16,10).rows.length,5)
  assert.deepEqual(catalogPage(rows.slice(0,3),16,10),{page:1,rows:rows.slice(0,3),pages:1})
  assert.equal(catalogPage([],9,20).page,1)
})
