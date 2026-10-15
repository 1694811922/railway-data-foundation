export function credentialPayload(form,now=Date.now()){
  const name=String(form.credential_name||'').trim(),reason=String(form.reason||'').trim()
  if(!form.subject_id)throw new Error('请选择已启用的需求方')
  if(!name||name.length>120)throw new Error('凭据名称必填，最多120字')
  if(!reason||reason.length>1000)throw new Error('操作原因必填，最多1000字')
  const expiry=new Date(form.expires_at)
  if(!form.expires_at||Number.isNaN(expiry.getTime())||expiry.getTime()<=now)throw new Error('到期时间必须晚于当前时间')
  return {credential_name:name,expires_at:expiry.toISOString(),reason}
}
export function assertCredential(row,expected={}){
  if(!row||row._demo||!/^AUTH_[a-f0-9]{32}$/.test(row.credential_id)||!/^SUB_[a-f0-9]{32}$/.test(row.subject_id)||!row.credential_name||!['ENABLED','REVOKED'].includes(row.status)||!Number.isInteger(row.version)||row.version<1
    ||Number.isNaN(new Date(row.expires_at).getTime())||!row.expires_at||['token','token_digest','password'].some(key=>Object.hasOwn(row,key)))throw new Error('凭据元数据不完整或包含不应回显的秘密')
  for(const key of ['credential_id','subject_id','status','version'])if(expected[key]!==undefined&&row[key]!==expected[key])throw new Error('凭据响应与当前操作不匹配，请刷新检查')
  if(row.status==='REVOKED'&&!row.revoked_at)throw new Error('撤销记录不完整')
  return row
}
export function assertIssued(result,subjectId,body){
  assertCredential(result?.credential,{subject_id:subjectId,status:'ENABLED',version:1})
  if(!/^rdf_[A-Za-z0-9_-]{43}$/.test(result.token)||result.credential.credential_name!==body.credential_name||new Date(result.credential.expires_at).getTime()!==new Date(body.expires_at).getTime())throw new Error('签发响应未确认，请刷新列表检查并撤销不明凭据')
  return result
}
export function credentialEvents(history,credential){
  assertCredential(credential)
  if(!Array.isArray(history)||history.length!==credential.version||history.length<1||history[0].action!=='ISSUE'
    ||history.some((event,i)=>!event||event.version!==i+1||event.action!==(i===0?'ISSUE':'REVOKE')||!event.reason||!event.operator_name||!event.occurred_at||Number.isNaN(new Date(event.occurred_at).getTime())||Object.hasOwn(event,'token')||Object.hasOwn(event,'token_digest'))
    ||(credential.status==='ENABLED'&&history.length!==1)||(credential.status==='REVOKED'&&history.at(-1).action!=='REVOKE'))throw new Error('凭据历史与当前状态不一致，请刷新检查')
  return history
}
export function invocationParams(form){
  const params={limit:Number(form.limit??10),offset:0}
  if(!Number.isInteger(params.limit)||params.limit<1||params.limit>100)throw new Error('返回条数须为1–100的整数')
  if(form.fields!=null){if(!Array.isArray(form.fields)||new Set(form.fields).size!==form.fields.length||form.fields.some(field=>typeof field!=='string'||!/^\w{1,64}$/.test(field)))throw new Error('请求字段须为不重复的字段名');if(form.fields.length)params.fields=form.fields.join(',')}
  if(form.range){if(!Array.isArray(form.range)||form.range.length!==2)throw new Error('请选择完整时间范围');const values=form.range.map(value=>new Date(value));if(values.some(value=>Number.isNaN(value.getTime()))||values[0]>=values[1])throw new Error('时间范围起止不合法');params.from=values[0].toISOString();params.to=values[1].toISOString()}
  for(const [key,pattern,label] of [['containerNo',/^[A-Za-z0-9]{1,20}$/,'集装箱号'],['trainNo',/^[A-Za-z0-9]{1,30}$/,'车次'],['eventType',/^(GATE_IN|GATE_OUT|LOAD|UNLOAD)$/,'事件类型']]){
    const value=String(form[key]??'').trim();if(value){if(!pattern.test(value))throw new Error(`${label}格式不正确`);params[key]=value}
  }
  return params
}

export function callerToken(value){
  const token=String(value||'').trim()
  if(!/^rdf_[A-Za-z0-9_-]{43}$/.test(token))throw new Error('请填写签发时获取的调用令牌，主体编号不能代替令牌')
  return token
}

// Check the actual projection, so an unexpected provider response is never presented as authorized.
export function providerRows(body,expectedFields,limit){
  if(body?.code!==0||!Array.isArray(body.data)||body.data.length>limit||!Array.isArray(expectedFields)||!expectedFields.length)
    throw new Error('提供服务响应不完整，未展示业务数据')
  const expected=new Set(expectedFields)
  if(expected.size!==expectedFields.length)throw new Error('允许字段范围不完整，请刷新关系')
  for(const row of body.data){
    if(!row||typeof row!=='object'||Array.isArray(row)||Object.keys(row).length!==expected.size||Object.keys(row).some(key=>!expected.has(key))
      ||Object.values(row).some(value=>value!==null&&!['string','number','boolean'].includes(typeof value)))
      throw new Error('返回字段与本次授权范围不一致，未展示业务数据')
  }
  return body.data
}
