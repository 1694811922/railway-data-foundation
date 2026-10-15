import test from 'node:test'
import assert from 'node:assert/strict'
import {downloadFile,downloadErrorBody} from '../src/utils/download.js'
const sample=(content='中文')=>{const data=new Blob([content],{type:'application/x-ndjson'});return {data,headers:{'content-type':'application/x-ndjson;charset=UTF-8','content-disposition':'attachment; filename="receiver-export-123.jsonl"','x-download-bytes':String(data.size),'x-export-count':'1'}}}
test('下载以实际字节长度和安全文件名核对',()=>{const file=downloadFile(sample());assert.equal(file.bytes,6);assert.equal(file.count,1);assert.equal(file.filename,'receiver-export-123.jsonl')})
test('空字节内容可以下载且不等于空值消息',()=>{const value=sample('');value.headers['content-type']='text/plain';value.headers['content-disposition']='attachment; filename="receiver-message-1-123.txt"';assert.equal(downloadFile(value).bytes,0)})
test('截断、残缺响应和越界不保存为成功',()=>{for(const change of [v=>v.headers['x-download-bytes']='99',v=>delete v.headers['content-disposition'],v=>v.headers['content-type']='text/html',v=>v.headers['x-export-count']='10001',v=>v.headers['content-disposition']='attachment; filename="../unsafe.html"']){const value=sample();change(value);assert.throws(()=>downloadFile(value))}})
test('JSON错误Blob仍按错误消息处理',async()=>{assert.deepEqual(await downloadErrorBody(new Blob(['{"code":403,"message":"无权下载","data":null}'])),{code:403,message:'无权下载',data:null});assert.equal(await downloadErrorBody(new Blob(['not-json'])),null)})
