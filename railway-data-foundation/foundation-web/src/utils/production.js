export const productionLabels={SUCCESS:'发送成功',NOT_SENT:'未发送',UNCONFIRMED:'结果待确认',PENDING:'未开始',SENDING:'发送中'}
export function validReceipt(value,topic){return value?.topic===topic&&Number.isInteger(value.partition)&&value.partition>=0&&Number.isSafeInteger(value.offset)&&value.offset>=0}
export function sendFailure(error){return [400,401,403,404,409,422].includes(error?.response?.status)?'NOT_SENT':'UNCONFIRMED'}
export function productionCounts(rows){return Object.fromEntries(['SUCCESS','NOT_SENT','UNCONFIRMED','PENDING'].map(state=>[state,rows.filter(row=>row.state===state).length]))}
export function productionFeedback(rows,{sending=false,cancelled=false}={}){
  const counts=productionCounts(rows),finished=counts.SUCCESS+counts.NOT_SENT+counts.UNCONFIRMED,total=rows.length
  const progress=total?Math.round(finished/total*100):0
  if(sending)return {type:'info',title:`正在发送：已处理 ${finished}/${total} 条`,progress}
  if(counts.UNCONFIRMED)return {type:'warning',title:`${counts.SUCCESS} 条发送成功，${counts.UNCONFIRMED} 条结果待确认；请核对记录后再决定是否重发`,progress}
  if(counts.NOT_SENT)return {type:'error',title:`${counts.SUCCESS} 条发送成功，${counts.NOT_SENT} 条未发送，${counts.PENDING} 条未开始`,progress}
  if(cancelled&&counts.PENDING)return {type:'warning',title:`已取消后续发送：${counts.SUCCESS} 条成功，${counts.PENDING} 条未开始`,progress}
  return {type:'success',title:`${counts.SUCCESS} 条消息已发送，Kafka 已确认接收`,progress}
}
export function recordPage(value){if(!Array.isArray(value?.items)||!Number.isSafeInteger(value.total)||value.total<0||value.items.some(row=>!Number.isSafeInteger(row.id)||row.id<1))throw new Error('源记录响应不完整');return value}
/** One bounded foreground batch. A late response can never start another send. */
export async function sendSelected(rows,{send,topic,current,cancelled,changed}){
  for(const row of rows){
    if(!current()||cancelled())break
    row.state='SENDING';changed()
    try{const receipt=await send(row.id);if(!current())return
      if(!validReceipt(receipt,topic))throw new Error('发送回执无效，请核对实际结果')
      row.state='SUCCESS';row.receipt=receipt
    }catch(error){if(!current())return;row.state=sendFailure(error);row.reason=error?.response?.data?.message||error.message||'发送结果尚未确认';changed();break}
    changed()
  }
}
