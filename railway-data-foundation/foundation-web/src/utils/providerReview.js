export const providerReviewActions = {'provider-confirm':'确认共享内容','provider-return':'退回申请'}
export function providerActionsFor(row) {
  if(row?.provider_review_mode!=='REQUIRED'||row.status!=='PENDING')return []
  return row.provider_confirmed_at?['provider-return']:['provider-confirm','provider-return']
}
export const providerReady=row=>row?.provider_review_mode!=='REQUIRED'||!!row.provider_confirmed_at
export function providerReviewLabel(row) {
  if(row?.provider_review_mode==='LEGACY')return '历史流程'
  if(row?.provider_review_mode==='ADMIN_MANAGED')return '管理员处理'
  if(row?.provider_review_mode!=='REQUIRED')return '状态未知'
  if(row.provider_confirmed_at)return '已确认'
  return {DRAFT:'待提交',REVOKED:'已撤回'}[row.status]||'待提供方确认'
}
export function assertProviderReview(row) {
  if(!['REQUIRED','ADMIN_MANAGED','LEGACY'].includes(row?.provider_review_mode))throw new Error('缺少提供方确认信息，请确认新版后端已启动')
  if(row.provider_confirmed_at!=null&&!Number.isFinite(Date.parse(row.provider_confirmed_at)))throw new Error('提供方确认时间不合法')
  if(row.provider_review_mode!=='REQUIRED'&&row.provider_confirmed_at!=null)throw new Error('历史或管理员流程不能伪造提供方确认')
  if(row.status==='DRAFT'&&row.provider_confirmed_at!=null)throw new Error('草稿仍带有过期的提供方确认')
  if(['CONFIRMED','ACTIVE','SUSPENDED','APPROVED'].includes(row.status)&&!providerReady(row))throw new Error('尚无提供方确认，不能表示管理员已审批')
  return row
}
