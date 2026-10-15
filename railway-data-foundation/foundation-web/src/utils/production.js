export const productionLabels={SUCCESS:'发送成功',NOT_SENT:'未发送',UNCONFIRMED:'结果待确认',PENDING:'未开始',SENDING:'发送中'}
export function validReceipt(value,topic){return value?.topic===topic&&Number.isInteger(value.partition)&&value.partition>=0&&Number.isSafeInteger(value.offset)&&value.offset>=0}
export function sendFailure(error){return [400,401,403,404,409,422].includes(error?.response?.status)?'NOT_SENT':'UNCONFIRMED'}
export function productionCounts(rows){return Object.fromEntries(['SUCCESS','NOT_SENT','UNCONFIRMED','PENDING'].map(state=>[state,rows.filter(row=>row.state===state).length]))}
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
