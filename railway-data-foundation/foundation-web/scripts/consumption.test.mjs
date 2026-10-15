import test from 'node:test'
import assert from 'node:assert/strict'
import { assertConsumptionPage } from '../src/utils/consumption.js'
const id='KSG_test', none=()=>({grant_id:id,summary:{state:'NOT_REPORTED',reports:0,records:null,bytes:null,last_received_at:null},items:[],total:0})
test('未上报数量保持未知，不能被零值、演示或其他申请替代',()=>{
  assert.equal(assertConsumptionPage(none(),id).summary.records,null)
  for(const patch of [{grant_id:'another'},{_demo:true},{summary:{...none().summary,records:0}},{summary:{...none().summary,state:'OBSERVED'}}])assert.throws(()=>assertConsumptionPage({...none(),...patch},id))
})
const date='2026-10-10T00:00:00Z', row=()=>({report_id:'12345678-1234-1234-1234-123456789abc',record_count:1,byte_count:320,grant_version:5,started_at:date,completed_at:date,received_at:date})
const observed=()=>({grant_id:id,summary:{state:'OBSERVED',reports:2,records:2,bytes:640,last_received_at:date},items:[row()],total:2})
test('观察分页和统计须一致，拒绝重复、敏感字段和非法数值',()=>{
  assert.equal(assertConsumptionPage(observed(),id,1,1).summary.bytes,640)
  for(const patch of [{total:0},{items:[row(),row()]},{summary:{...observed().summary,bytes:-1}},{items:[{...row(),password:'forbidden'}]},{items:[{...row(),byte_count:1.2}]}])assert.throws(()=>assertConsumptionPage({...observed(),...patch},id))
})
