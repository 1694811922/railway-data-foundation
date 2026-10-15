import test from 'node:test'
import assert from 'node:assert/strict'
import {allowedActions} from '../src/utils/workspace.js'
import {assertProviderReview,providerReviewLabel,providerActionsFor,providerReady} from '../src/utils/providerReview.js'
import {actionsFor} from '../src/utils/sharing.js'
import {subscriptionActionsFor} from '../src/utils/subscription.js'
const pending=()=>({status:'PENDING',provider_review_mode:'REQUIRED',provider_confirmed_at:null})
const confirmed=()=>({...pending(),provider_confirmed_at:'2026-10-12T12:00:00Z'})
for(const [name,actions] of [['REST',actionsFor],['Kafka',subscriptionActionsFor]]){
  test(`${name}提供方确认与管理员审批分开，需求方不能审批，管理员不能冒充提供方`,()=>{
    const options=actions(pending())
    assert.deepEqual(allowedActions('PROVIDER',options),['provider-confirm','provider-return'])
    assert.deepEqual(allowedActions('DEMAND',options),['revoke'])
    assert.equal(allowedActions('ADMIN',options).includes('confirm'),false)
    assert.equal(allowedActions('ADMIN',actions(confirmed())).includes('confirm'),true)
    assert.deepEqual(allowedActions('PROVIDER',actions(confirmed())),['provider-return'])
    assert.equal(allowedActions('ADMIN',actions(confirmed())).some(a=>a.startsWith('provider-')),false)
  })
}
test('历史流程和无归属管理员处理不伪装为提供方确认，不增加提供方操作',()=>{
  for(const mode of ['LEGACY','ADMIN_MANAGED']){
    const row={...pending(),provider_review_mode:mode}
    assert.equal(providerReady(row),true);assert.deepEqual(providerActionsFor(row),[])
    assert.equal(allowedActions('ADMIN',subscriptionActionsFor(row)).includes('confirm'),true)
    assert.notEqual(providerReviewLabel(row),'已确认')
    assert.throws(()=>assertProviderReview({...row,provider_confirmed_at:confirmed().provider_confirmed_at}),/伪造/)
  }
})
test('未完成提供方确认的审批成功响应、草稿残留旧确认、未知或缺失模式被拒绝',()=>{
  for(const status of ['CONFIRMED','ACTIVE','SUSPENDED','APPROVED'])assert.throws(()=>assertProviderReview({...pending(),status}),/尚无/)
  assert.throws(()=>assertProviderReview({...confirmed(),status:'DRAFT'}),/过期/)
  for(const mode of [undefined,'auto'])assert.throws(()=>assertProviderReview({...pending(),provider_review_mode:mode}),/新版后端/)
  assert.throws(()=>assertProviderReview({...confirmed(),provider_confirmed_at:'bad'}),/时间/)
})
test('提供方确认保留待审批状态，退回或撤回不继续提供确认入口',()=>{
  assert.equal(assertProviderReview(confirmed()).status,'PENDING')
  assert.equal(providerReviewLabel(confirmed()),'已确认')
  assert.equal(providerReviewLabel({...pending(),status:'DRAFT'}),'待提交')
  for(const status of ['DRAFT','APPROVED','REVOKED'])assert.deepEqual(providerActionsFor({...confirmed(),status}),[])
})
