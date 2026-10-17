export const transferMethods={REST:'REST接口',KAFKA:'Kafka消息'}
export const transferStages={REST_QUERY:'查询返回',KAFKA_SEND:'消息发送',KAFKA_STORE:'接收落库',KAFKA_CHECKPOINT:'位置提交',KAFKA_REPORT:'摘要上报',KAFKA_TASK:'任务状态'}
export const durationScopes={REST_EXECUTION:'接口处理耗时',KAFKA_SEND_EXECUTION:'提供方发送请求处理，含准入核验、读源、序列化和Kafka回执等待',KAFKA_ACK:'提供方发送请求处理，含准入核验、读源、序列化和Kafka回执等待',DB_TRANSACTION:'批次入库事务开始至提交返回，不含Kafka等待和位置提交'}
export const summaryLabels={rest_calls:'REST接口调用次数',kafka_sent:'Kafka发送成功消息数',received_records:'接收新增落库条数',error_events:'异常事件数'}
const integer=v=>Number.isSafeInteger(v)&&v>=0
const timestamp=v=>typeof v==='string'&&/(Z|[+-]\d\d:\d\d)$/.test(v)&&Number.isFinite(Date.parse(v))
const fail=()=>{throw new Error('传输监测响应不完整，请重试')}
export function monitorRange(now=new Date()){const start=new Date(now);start.setHours(0,0,0,0);return [start,now]}
export function transferQuery(form){
 const range=form.range?.map(v=>new Date(v).getTime())
 if(!range||range.length!==2||range.some(v=>!Number.isFinite(v))||range[0]>=range[1]||range[1]-range[0]>86400000)throw new Error('请选择有效时间范围，最多24小时')
 const q={from:new Date(range[0]).toISOString(),until:new Date(range[1]).toISOString()}
 for(const [key,values] of Object.entries({method:transferMethods,stage:transferStages,outcome:{SUCCESS:1,DENIED:1,ERROR:1,INFO:1,WARNING:1}}))if(form[key]){if(!Object.hasOwn(values,form[key]))throw new Error('筛选条件无效');q[key]=form[key]}
 for(const [key,regex,label] of [['resourceId',/^RES_[a-f0-9]{32}$/,'资源'],['taskId',/^RCV_[a-f0-9]{32}$/,'任务'],['traceId',/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/,'追踪']])if(form[key]?.trim()){if(!regex.test(form[key].trim()))throw new Error(`请填写完整${label}编号`);q[key]=form[key].trim()}
 return q
}
export function transferMonitorPage(data,params){
 if(!data||data.source!=='REPORTED_TECHNICAL_EVENTS'||typeof data.receiver_configured!=='boolean'||!integer(data.total)||!Array.isArray(data.items)||data.items.length>params.limit||data.items.length>Math.max(0,data.total-params.offset))fail()
 if(!data.summary)fail()
 for(const key of Object.keys(summaryLabels))if(data.summary[key]!==null&&!integer(data.summary[key]))fail()
 const ids=new Set()
 for(const r of data.items){
  if(!r||!['RUNTIME','RECEIVER'].includes(r.origin)||!transferMethods[r.method]||!transferStages[r.stage]||!['SUCCESS','DENIED','ERROR','INFO','WARNING'].includes(r.outcome)||!timestamp(r.occurred_at)||!timestamp(r.received_at)||!Array.isArray(r.positions)||typeof r.entry_id!=='string'||ids.has(r.entry_id))fail()
  ids.add(r.entry_id)
  if(r.origin==='RUNTIME'&&(!r.entry_id.startsWith('runtime:')||!['REST_QUERY','KAFKA_SEND'].includes(r.stage))||r.origin==='RECEIVER'&&(!r.entry_id.startsWith('receiver:')||r.method!=='KAFKA')||r.method==='REST'&&r.stage!=='REST_QUERY'||r.method==='KAFKA'&&r.stage==='REST_QUERY')fail()
  for(const k of ['record_count','byte_count','duration_ms','inserted_count','duplicate_count'])if(r[k]!=null&&!integer(r[k]))fail()
  if(r.duration_scope!=null&&!durationScopes[r.duration_scope]||r.duration_ms!=null&&!r.duration_scope)fail()
  if(r.origin==='RECEIVER'&&(!/^RCV_[a-f0-9]{32}$/.test(r.task_id)||!/^KSG_[a-f0-9]{32}$/.test(r.grant_id)))fail()
  for(const k of ['method','stage','outcome','taskId','resourceId','traceId']){const field={taskId:'task_id',resourceId:'resource_id',traceId:'trace_id'}[k]||k;if(params[k]&&params[k]!==r[field])fail()}
  if(Date.parse(r.occurred_at)<Date.parse(params.from)||Date.parse(r.occurred_at)>=Date.parse(params.until))fail()
  if(r.positions.some(p=>!integer(p.partition)||!integer(p.next_offset)))fail()
  for(const k of ['password','token','message_value','message_key','headers_json','encrypted_secrets','context_json'])if(Object.hasOwn(r,k))fail()
 }
 return data
}
