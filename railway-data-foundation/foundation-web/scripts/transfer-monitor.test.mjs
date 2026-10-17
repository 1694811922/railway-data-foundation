import test from 'node:test'
import assert from 'node:assert/strict'
import {transferQuery,transferMonitorPage,monitorRange} from '../src/utils/transferMonitor.js'
import {menus,allowedRoute} from '../src/utils/workspace.js'
const from='2026-10-16T00:00:00.000Z',until='2026-10-16T01:00:00.000Z',resourceId='RES_'+'a'.repeat(32)
const query={from,until,limit:20,offset:0}
const row=()=>({entry_id:'runtime:1',origin:'RUNTIME',method:'REST',stage:'REST_QUERY',outcome:'SUCCESS',occurred_at:from,received_at:until,positions:[],record_count:2,byte_count:30,duration_ms:6,duration_scope:'REST_EXECUTION',resource_id:resourceId})
const data=()=>({source:'REPORTED_TECHNICAL_EVENTS',receiver_configured:true,total:1,items:[row()],summary:{rest_calls:1,kafka_sent:null,received_records:null,error_events:0}})
test('管理员只保留一个监测菜单，旧地址兼容且不能给普通身份放权',()=>{
 const group=menus.ADMIN.find(([label])=>label==='运行监管')[1];assert.deepEqual(group,[['/transfer-monitor','传输监测']])
 for(const role of ['PROVIDER','DEMAND'])assert.equal(allowedRoute(role,'/transfer-monitor'),false)
 for(const path of ['/transfer-monitor','/runtime-events','/runtime-traces/id','/metrics','/kafka-monitor'])assert.equal(allowedRoute('ADMIN',path),true)
})
test('默认当天时间与最多24小时，格式和角色参数不混入查询',()=>{
 const now=new Date('2026-10-16T12:30:00Z'),range=monitorRange(now);assert.equal(range[0].getHours(),0);assert.equal(range[1],now)
 assert.deepEqual(transferQuery({range:[from,until],method:'REST',subjectId:'forged'}),{from,until,method:'REST'})
 for(const value of [{range:[until,from]},{range:[from,'2026-10-18']},{range:[from,until],method:'CDC'},{range:[from,until],resourceId:'bad'}])assert.throws(()=>transferQuery(value))
})
test('混合记录分页保留服务器总数，统计未知不补成零',()=>{
 const result=transferMonitorPage(data(),query);assert.equal(result.summary.kafka_sent,null);assert.equal(result.summary.error_events,0)
 assert.equal(transferMonitorPage({...data(),total:32,items:[]},{...query,offset:40}).total,32)
 assert.throws(()=>transferMonitorPage({...data(),total:0},query))
})
test('筛选与时间范围不匹配的迟到数据不能展示',()=>{
 for(const params of [{...query,method:'KAFKA'},{...query,stage:'KAFKA_SEND'},{...query,resourceId:'RES_'+'b'.repeat(32)},{...query,outcome:'ERROR'}])assert.throws(()=>transferMonitorPage(data(),params))
 const value=data();value.items[0].occurred_at=until;assert.throws(()=>transferMonitorPage(value,query))
})
test('耗时范围与来源严格区分，缺采集的批次时间保留未知',()=>{
 const value=data();value.items[0]={...row(),entry_id:'receiver:n:1',origin:'RECEIVER',method:'KAFKA',stage:'KAFKA_STORE',task_id:'RCV_'+'a'.repeat(32),grant_id:'KSG_'+'b'.repeat(32),duration_ms:null,duration_scope:'DB_TRANSACTION'}
 assert.equal(transferMonitorPage(value,query).items[0].duration_ms,null)
 value.items[0].duration_ms=-1;assert.throws(()=>transferMonitorPage(value,query))
})
test('秘密字段、伪造来源、重复行及无计数不能成为成功记录',()=>{
 for(const modify of [v=>v.items[0].password='hidden',v=>v.items[0].message_value='hidden',v=>v.source='TRUSTED_BILLING',v=>delete v.summary.rest_calls,v=>v.items.push({...v.items[0]}),v=>v.items[0].method='CDC',v=>v.items[0].duration_scope='END_TO_END']){
  const value=data();modify(value);assert.throws(()=>transferMonitorPage(value,query))
 }
})
