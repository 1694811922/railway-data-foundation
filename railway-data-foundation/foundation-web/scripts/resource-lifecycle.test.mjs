import test from 'node:test'
import assert from 'node:assert/strict'
import {lifecycleActions,lifecycleBody,lifecycleImpact} from '../src/utils/resourceLifecycle.js'
import {catalogQuery,filterResources} from '../src/utils/resourceCatalog.js'
import {fieldEditScope} from '../src/utils/resourceDetail.js'
import {registrationEditScope} from '../src/utils/resourceRegistration.js'
import {historyActions} from '../src/utils/resourceHistory.js'
const resource={resource_id:'RES_'+'a'.repeat(32),version:3,status:'OFFLINE'}
const impact={resource_id:resource.resource_id,resource_version:3,status:'OFFLINE',capabilities:2,active_rest_relationships:0,kafka_executions:1,kafka_revoke_pending:1,can_delete:false}
test('资源状态只允许明确迁移，退役不可恢复',()=>{
 assert.deepEqual(lifecycleActions('DRAFT'),['publish']);assert.deepEqual(lifecycleActions('PUBLISHED'),['unpublish']);assert.deepEqual(lifecycleActions('OFFLINE'),['republish','retire']);assert.deepEqual(lifecycleActions('RETIRED'),[])
 assert.throws(()=>lifecycleBody({...resource,status:'RETIRED'},'republish','错误恢复'))
})
test('操作提交读取版本及原因，不接受空白或错误状态',()=>{
 assert.deepEqual(lifecycleBody(resource,'republish','  维护完成  '),{version:3,reason:'维护完成'})
 for(const args of [[resource,'publish','错误操作'],[resource,'retire',' '],[resource,'retire','x'.repeat(501)],[{...resource,version:null},'republish','原因']])assert.throws(()=>lifecycleBody(...args))
})
test('影响预览绑定版本、资源及真实演示模式，撤权待完成不能伪装完成',()=>{
 assert.equal(lifecycleImpact({value:impact,demo:false},resource,false).kafka_revoke_pending,1)
 for(const patch of [{resource_id:'foreign'},{resource_version:2},{status:'PUBLISHED'},{kafka_revoke_pending:-1},{capabilities:'2'}])assert.throws(()=>lifecycleImpact({value:{...impact,...patch},demo:false},resource,false))
 assert.throws(()=>lifecycleImpact({value:impact,demo:true},resource,false))
})
test('目录四状态过滤与退役资源编辑字段均被拒绝',()=>{
 assert.equal(catalogQuery({status:'OFFLINE'}).filters.status,'OFFLINE');assert.equal(catalogQuery({status:'RETIRED'}).filters.status,'RETIRED')
 assert.deepEqual(filterResources([resource,{...resource,status:'RETIRED'}],{status:'RETIRED'}).map(r=>r.status),['RETIRED'])
 assert.match(registrationEditScope({...resource,status:'RETIRED'},null,[],false),/退役/);assert.match(fieldEditScope(null,{...resource,status:'RETIRED'},[],false),/退役/)
 assert.equal(historyActions.UNPUBLISH,'资源下架');assert.equal(historyActions.REPUBLISH,'重新发布');assert.equal(historyActions.RETIRE,'资源退役')
})
