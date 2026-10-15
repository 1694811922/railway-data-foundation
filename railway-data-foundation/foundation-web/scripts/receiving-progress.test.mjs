import test from 'node:test'
import assert from 'node:assert/strict'
import {receivingProgress,progressLabels} from '../src/utils/receivingProgress.js'
const sample=()=>({task:{task_id:'RCV_own'},topic:'own.topic',summary:{status:'CAUGHT_UP',partition_count:1,remaining_positions:0},partitions:[{partition:0,earliest_offset:0,latest_offset:5,next_offset:5,committed_offset:null,remaining_positions:0,status:'CAUGHT_UP'}]})
test('零位置差和未知提交位置分别保留',()=>{const data=receivingProgress(sample());assert.equal(data.summary.remaining_positions,0);assert.equal(data.partitions[0].committed_offset,null)})
test('缺失检查点不显示虚假零',()=>{const data=sample();data.summary.status='NOT_INITIALIZED';data.summary.remaining_positions=null;Object.assign(data.partitions[0],{next_offset:null,remaining_positions:null,status:'NOT_INITIALIZED'});assert.equal(receivingProgress(data).summary.remaining_positions,null)})
test('残缺、错误类型和重复分区不能显示成功',()=>{for(const edit of [d=>d.partitions=[],d=>d.summary.remaining_positions='0',d=>d.partitions.push(d.partitions[0]),d=>d.partitions[0].latest_offset=-1,d=>d.partitions[0].status='invented']){const d=sample();edit(d);assert.throws(()=>receivingProgress(d))}})
test('中文区分主题无可用消息和追平位置',()=>{assert.match(progressLabels.NO_AVAILABLE_MESSAGES,/无可用消息/);assert.match(progressLabels.CAUGHT_UP,/查询时/);assert.match(progressLabels.POSITION_INVALID,/范围/)})
