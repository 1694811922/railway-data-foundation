import test from 'node:test'
import assert from 'node:assert/strict'
import {transferPage,monitorPage,logQuery,commitStates,transferReasons} from '../src/utils/transferLogs.js'
import {receivingReadGate} from '../src/utils/receiving.js'
import {allowedRoute} from '../src/utils/workspace.js'
const task='RCV_'+'a'.repeat(32),grant='KSG_'+'b'.repeat(32)
const row=()=>({log_id:1,task_id:task,grant_id:grant,event_type:'BATCH_STORED',outcome:'SUCCESS',reason_code:'DATABASE_COMMITTED',record_count:2,inserted_count:1,duplicate_count:1,byte_count:32,commit_state:'UNKNOWN',report_state:'PENDING',positions:[{partition:0,first_offset:4,last_offset:5,next_offset:6}]})
const params={limit:20,offset:0,taskId:task}
test('落库、去重、位置未知和摘要待补报分别保留',()=>{const result=transferPage({items:[row()],total:1},params);assert.equal(result.items[0].commit_state,'UNKNOWN');assert.equal(commitStates.UNKNOWN,'未知');assert.match(transferReasons.KAFKA_COMMIT_UNKNOWN,/落库成功/);assert.equal(result.items[0].duplicate_count,1)})
test('筛选分页参数与主体任务响应必须一致',()=>{assert.equal(transferPage({items:[],total:21},{limit:20,offset:40}).total,21);assert.throws(()=>transferPage({items:[row()],total:1},{...params,taskId:'RCV_'+'c'.repeat(32)}));assert.throws(()=>transferPage({items:[row()],total:1},{...params,eventType:'STOPPED'}));assert.throws(()=>transferPage({items:[row()],total:1},{...params,outcome:'ERROR'}));assert.throws(()=>transferPage({items:[row()],total:0},params))})
test('残缺日志、未知状态、重复分区或秘密字段不能进入展示',()=>{for(const edit of [r=>r.commit_state='SUCCESS',r=>r.record_count=3,r=>r.positions[0].next_offset=5,r=>r.positions.push({...r.positions[0]}),r=>r.password='secret',r=>r.reason_code='free error',r=>r.byte_count=-1]){const value=row();edit(value);assert.throws(()=>transferPage({items:[value],total:1},params))}})
test('查询时间与完整任务编号校验，参数不包含身份',()=>{assert.deepEqual(logQuery({taskId:task,eventType:'BATCH_STORED',outcome:'SUCCESS',range:[],subjectId:'forged'}),{taskId:task,eventType:'BATCH_STORED',outcome:'SUCCESS'});assert.throws(()=>logQuery({taskId:'bad'}));assert.throws(()=>logQuery({range:['2026-10-02','2026-10-01']}))})
test('刷新、任务切换和离开后旧读取不可覆盖当前日志',async()=>{const gate=receivingReadGate();let release;const first=gate.next();const pending=new Promise(resolve=>{release=resolve});const result=pending.then(()=>gate.current(first));gate.next();release();assert.equal(await result,false);const next=gate.next();gate.dispose();assert.equal(gate.current(next),false)})
test('仅管理员进入平台监测，供需双方仍保持原业务权限',()=>{assert.equal(allowedRoute('ADMIN','/kafka-monitor'),true);for(const role of ['PROVIDER','DEMAND'])assert.equal(allowedRoute(role,'/kafka-monitor'),false);assert.equal(allowedRoute('ADMIN','/receiving'),false);assert.equal(allowedRoute('PROVIDER','/receiving'),false)})

test('管理员可查询自报事件和未知上报状态，授权编号不能错配',()=>{const r={...row(),report_state:'UNKNOWN'};assert.equal(transferPage({items:[r],total:1},{...params,grantId:grant}).items[0].report_state,'UNKNOWN');assert.throws(()=>transferPage({items:[r],total:1},{...params,grantId:'KSG_'+'c'.repeat(32)}));assert.deepEqual(logQuery({grantId:grant}),{grantId:grant});assert.throws(()=>logQuery({grantId:'bad'}))})

test('管理员监管标识与接入状态缺失时不能显示完整日志',()=>{const data={items:[row()],total:1,configured:false,source:'RECEIVER_SELF_REPORTED',last_received_at:null};assert.equal(monitorPage(data,params).configured,false);for(const patch of [{configured:'true'},{source:'TRUSTED_BILLING'},{last_received_at:'bad'}])assert.throws(()=>monitorPage({...data,...patch},params))})
