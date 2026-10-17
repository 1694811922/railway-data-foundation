import test from 'node:test'
import assert from 'node:assert/strict'
import {authorizationRedirect,authorizationMethod,authorizationTabQuery} from '../src/utils/authorizationNavigation.js'
import {allowedRoute,menus} from '../src/utils/workspace.js'
import {restCapabilities} from '../src/utils/sharing.js'
test('管理员共享治理收敛为授权和REST凭据，身份归系统管理',()=>{
 assert.deepEqual(menus.ADMIN.find(([name])=>name==='共享治理')[1],[['/sharing-authorizations','共享授权'],['/credentials','REST调用凭据']])
 assert.deepEqual(menus.ADMIN.find(([name])=>name==='系统管理')[1],[['/subjects','共享主体管理'],['/accounts','用户账号']])
 assert.equal(allowedRoute('ADMIN','/sharing-authorizations'),true)
 for(const role of ['PROVIDER','DEMAND'])assert.equal(allowedRoute(role,'/sharing-authorizations'),false)
})
test('旧管理员链接保留授权和资源参数，供需双方入口保持独立',()=>{
 const query={relationshipId:'REL_a',resource:'RES_a',capability:'CAP_a',keyword:'关键词'}
 for(const [path,method,section] of [['/relationships','REST'],['/kafka-subscriptions','KAFKA'],['/policies','REST','policy'],['/access-check','REST','test']]){
  const result=authorizationRedirect('ADMIN',path,query);assert.equal(result.path,'/sharing-authorizations');assert.deepEqual(result.query,{...query,method,...(section?{section}:{})});assert.equal(result.replace,true)
  for(const role of ['DEMAND','PROVIDER'])assert.equal(authorizationRedirect(role,path,query),null)
 }
 assert.equal(authorizationRedirect('ADMIN','/credentials'),null)
})
test('页签通过网址恢复，切换时清除上一种授权的临时入口参数',()=>{
 assert.equal(authorizationMethod({method:'KAFKA'}),'KAFKA');assert.equal(authorizationMethod({method:'bad'}),'REST')
 assert.deepEqual(authorizationTabQuery('KAFKA'),{method:'KAFKA'});assert.deepEqual(authorizationTabQuery('REST'),{method:'REST'})
})
test('REST表单拒绝Kafka和其他能力，不混入其他资源或退役能力',()=>{
 const rows=[{capability_id:'r',resource_id:'a',type:'REST_API',status:'DRAFT'},...['KAFKA_TOPIC','CDC_STREAM','READONLY_QUERY'].map(type=>({resource_id:'a',type,status:'ENABLED'})),{resource_id:'b',type:'REST_API',status:'ENABLED'},{resource_id:'a',type:'REST_API',status:'RETIRED'}]
 assert.deepEqual(restCapabilities(rows,'a').map(row=>row.capability_id),['r'])
})
