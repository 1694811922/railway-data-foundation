import { test } from 'node:test'
import assert from 'node:assert/strict'
import { demoStore, demoSeed, isDemoResource } from '../src/mock/demoData.js'
import { validateResource, resourcePayload, resourceError } from '../src/utils/resourceForm.js'

// Browser storage stand-in; unit checks never contact backend or persist user records.
const storage = new Map()
globalThis.localStorage = { getItem:key=>storage.get(key) ?? null, setItem:(key,value)=>storage.set(key,value) }
test('demo field edits are scoped to resource, not global scan fields', () => {
  storage.clear()
  const second = { ...demoSeed.resources[0], resource_id:'RES_DEMO_TEST', resource_name:'另一演示资源' }
  demoStore.put('resources', second, 'resource_id')
  const original = demoStore.resourceFields('RES_000001').find(f=>f.field_name==='container_no')
  demoStore.saveResourceField('RES_DEMO_TEST','container_no',{ business_name:'另一资源字段名称' })
  assert.equal(demoStore.resourceFields('RES_DEMO_TEST').find(f=>f.field_name==='container_no').business_name,'另一资源字段名称')
  assert.equal(demoStore.resourceFields('RES_000001').find(f=>f.field_name==='container_no').business_name,original.business_name)
  assert.equal(demoStore.list('fields').find(f=>f.field_name==='container_no').business_name,original.business_name)
  assert.equal(demoStore.list('resources').find(r=>r.resource_id===second.resource_id).version,2)
})
test('only owned demo IDs select demo resource mode', () => {
  assert.equal(isDemoResource('RES_000001'),true)
  assert.equal(isDemoResource('RES_DEMO_MISSING'),false)
  assert.equal(isDemoResource('RES_'+'a'.repeat(32)),false)
  assert.deepEqual(demoStore.resourceFields('RES_missing'),[])
  assert.throws(()=>demoStore.saveResourceField('RES_missing','container_no',{}),/不存在/)
})
test('resource validation requires scanned origin and manual business information', () => {
  const resource = demoSeed.resources[0]
  assert.equal(validateResource(resource),'')
  for (const key of ['source_id','schema_name','physical_table','resource_name','owner_department','business_description'])
    assert.notEqual(validateResource({...resource,[key]:' '}),'')
  assert.equal('source_name' in resourcePayload(resource),false)
  assert.equal('status' in resourcePayload(resource),false)
  assert.match(resourceError({code:'ECONNABORTED'},'保存资源'),/结果尚未确认/)
  assert.equal(resourceError({response:{data:{message:'该表已登记'}}},'保存资源'),'该表已登记')
})
