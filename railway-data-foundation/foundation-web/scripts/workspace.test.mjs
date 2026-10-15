import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { allowedRoute,allowedActions,menus } from '../src/utils/workspace.js'
test('角色菜单与路由一致，需求方没有后台配置入口',()=>{
 assert.equal(allowedRoute('DEMAND','/resources/RES_abc'),true)
 for(const path of ['/sources','/scans','/accounts','/capabilities','/policies','/metrics','/runtime-events','/access-check'])assert.equal(allowedRoute('DEMAND',path),false)
 assert.equal(allowedRoute('PROVIDER','/accounts'),false)
 assert.equal(allowedRoute('PROVIDER','/runtime-traces/uuid'),true)
 assert.equal(allowedRoute('ADMIN','/accounts'),true)
 assert.equal(allowedRoute('invented','/dashboard'),false)
 assert.equal(Object.keys(menus).length,3)
})
test('需求方不可最终审批；提供方仅确认内容或退回；管理员不冒充提供方',()=>{
 assert.deepEqual(allowedActions('DEMAND',['submit','confirm','return','enable','suspend','revoke']),['submit','revoke'])
 assert.deepEqual(allowedActions('DEMAND',['provision','stop','retry','credential'],'execution'),['stop','credential'])
 assert.deepEqual(allowedActions('PROVIDER',['submit','revoke','credential']),[])
 assert.deepEqual(allowedActions('ADMIN',['confirm','provision']),['confirm','provision'])
 assert.deepEqual(allowedActions('PROVIDER',['provider-confirm','provider-return','confirm','enable']),['provider-confirm','provider-return'])
 assert.deepEqual(allowedActions('ADMIN',['provider-confirm','provider-return','confirm']),['confirm'])
})
test('真实请求不回退演示，身份变化隔离晚到响应并清空敏感视图',async()=>{
 const request=await readFile(new URL('../src/utils/request.js',import.meta.url),'utf8'),layout=await readFile(new URL('../src/layout/MainLayout.vue',import.meta.url),'utf8'),state=await readFile(new URL('../src/utils/sessionState.js',import.meta.url),'utf8')
 assert.match(request,/identityEpoch!==session.epoch/)
 assert.match(request,/if\(explicitDemo\)throw/)
 assert.match(request,/return explicitDemo/)
 assert.match(layout,/:key="session.epoch"/)
 assert.match(state,/session.csrf = null; session.epoch\+\+/)
 assert.doesNotMatch(state,/localStorage|sessionStorage/)
})
