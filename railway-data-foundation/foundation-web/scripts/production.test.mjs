import test from 'node:test'
import assert from 'node:assert/strict'
import {sendSelected,productionCounts,productionFeedback,sendFailure,recordPage,validReceipt} from '../src/utils/production.js'
import {allowedRoute} from '../src/utils/workspace.js'
const topic='real.topic',receipt={topic,partition:0,offset:0}
const rows=()=>[1,2,3].map(id=>({id,state:'PENDING'}))
const options=send=>({send,topic,current:()=>true,cancelled:()=>false,changed:()=>{}})
test('只有提供方可进入数据发送页面',()=>{assert.equal(allowedRoute('PROVIDER','/production'),true);assert.equal(allowedRoute('DEMAND','/production'),false);assert.equal(allowedRoute('ADMIN','/production'),false)})
test('真实分页总数和记录响应必须完整',()=>{assert.equal(recordPage({items:[{id:1}],total:21}).total,21);assert.throws(()=>recordPage({items:[{}],total:1}));assert.throws(()=>recordPage({items:[],total:'0'}))})
test('真实回执保留零位置且必须对应主题',()=>{assert.equal(validReceipt(receipt,topic),true);assert.equal(validReceipt({...receipt,topic:'other'},topic),false);assert.equal(validReceipt({...receipt,offset:null},topic),false)})
test('批量按顺序执行并逐条确认',async()=>{const data=rows(),calls=[];await sendSelected(data,options(async id=>{calls.push(id);return {...receipt,offset:id}}));assert.deepEqual(calls,[1,2,3]);assert.equal(productionCounts(data).SUCCESS,3)})
test('明确拒绝标为未发送，保留成功和未开始项',async()=>{const data=rows();await sendSelected(data,options(async id=>{if(id===2)throw {response:{status:403,data:{message:'拒绝'}}};return receipt}));assert.deepEqual(data.map(x=>x.state),['SUCCESS','NOT_SENT','PENDING'])})
test('超时或服务错误不自动重试，暂停后续发送',async()=>{for(const error of [{code:'ECONNABORTED'},{response:{status:503}}]){let count=0;const data=rows();await sendSelected(data,options(async()=>{count++;throw error}));assert.equal(count,1);assert.equal(data[0].state,'UNCONFIRMED');assert.equal(data[1].state,'PENDING');assert.equal(sendFailure(error),'UNCONFIRMED')}})
test('取消只影响后续项，在途成功回执仍显示',async()=>{let cancel=false;const data=rows();await sendSelected(data,{...options(async()=>{cancel=true;return receipt}),cancelled:()=>cancel});assert.deepEqual(data.map(x=>x.state),['SUCCESS','PENDING','PENDING'])})
test('切换身份或离开后，晚到响应不覆盖且不触发下一发送',async()=>{let current=true,count=0;const data=rows();await sendSelected(data,{...options(async()=>{count++;current=false;return receipt}),current:()=>current});assert.equal(count,1);assert.equal(data[0].state,'SENDING');assert.equal(data[1].state,'PENDING')})

test('发送进度与完成提示区分全部成功、部分失败、取消及待确认',()=>{
 assert.deepEqual(productionFeedback([{state:'SUCCESS'},{state:'SENDING'}],{sending:true}),{type:'info',title:'正在发送：已处理 1/2 条',progress:50})
 assert.equal(productionFeedback([{state:'SUCCESS'}]).type,'success')
 assert.equal(productionFeedback([{state:'SUCCESS'},{state:'NOT_SENT'},{state:'PENDING'}]).type,'error')
 assert.match(productionFeedback([{state:'UNCONFIRMED'},{state:'PENDING'}]).title,/请核对记录/)
 const cancel=productionFeedback([{state:'SUCCESS'},{state:'PENDING'}],{cancelled:true});assert.equal(cancel.type,'warning');assert.equal(cancel.progress,50);assert.match(cancel.title,/已取消/)
})
