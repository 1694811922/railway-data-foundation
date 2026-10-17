export const collectionPoints={REST_PROVIDER:'REST接口',KAFKA_PRODUCER:'Kafka消息'}
export const outcomes={SUCCESS:'成功',DENIED:'拒绝',ERROR:'异常'}
export const windowStates={MISSING:'未上报',PARTIAL:'部分上报',CLOSED:'已关闭',UNKNOWN:'未知'}
export const reasons={COMPLETED:'执行成功',IDENTITY_INVALID:'调用身份无效',RELATIONSHIP_UNAVAILABLE:'共享关系不可用',SUBJECT_UNAVAILABLE:'参与主体已停用',RELATIONSHIP_INACTIVE:'共享关系未启用',RELATIONSHIP_EXPIRED:'授权已过期',POLICY_UNAVAILABLE:'访问策略不可用',FIELDS_FORBIDDEN:'字段超出授权范围',TIME_FORBIDDEN:'时间超出授权范围',CAPABILITY_UNAVAILABLE:'接口能力不可用',INVALID_REQUEST:'请求参数无效',OPERATOR_FORBIDDEN:'操作身份无效',SOURCE_UNAVAILABLE:'业务源连接失败',CONTROL_UNAVAILABLE:'授权服务不可用',SERIALIZATION_ERROR:'响应输出失败',INTERNAL_ERROR:'执行异常',KAFKA_UNAVAILABLE:'消息服务器不可用'}
export const scopeTypes={resourceId:{label:'数据资源',prefix:'RES'},capabilityId:{label:'接口能力',prefix:'CAP'},relationshipId:{label:'共享关系',prefix:'REL'},subjectId:{label:'需求方',prefix:'SUB'}}
export const uuidPattern=/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
const integer=value=>Number.isSafeInteger(value)&&value>=0
const time=value=>typeof value==='string'&&/(Z|[+-]\d\d:\d\d)$/.test(value)&&Number.isFinite(Date.parse(value))
const fail=()=>{throw new Error('运行数据格式或归属不正确，请重新读取')}
const eventFields='event_id report_id trace_id collector_id collection_point relationship_id capability_id subject_id resource_id policy_id relationship_version policy_version started_at completed_at outcome reason_code http_status record_count byte_count duration_ms topic topic_partition topic_offset received_at'.split(' ')
export const quantity=value=>value===null||value===undefined?'—':value.toLocaleString('zh-CN')
export const tagType=value=>({SUCCESS:'success',DENIED:'warning',ERROR:'danger',CLOSED:'success',PARTIAL:'warning',MISSING:'info',UNKNOWN:'info'}[value]||'info')
export function defaultRange(now=Date.now()){const until=Math.floor(now/60000)*60000;return [new Date(until-15*60000),new Date(until)]}
export function filterForm(metrics=false){return {collectionPoint:'REST_PROVIDER',scopeType:'resourceId',scopeId:'',outcome:'',traceId:'',range:metrics?defaultRange():null}}
export function runtimeQuery(form,metrics=false){
  if(!Object.hasOwn(collectionPoints,form.collectionPoint)||!Object.hasOwn(scopeTypes,form.scopeType))throw new Error('请选择采集位置和筛选对象')
  const params={collectionPoint:form.collectionPoint},id=form.scopeId.trim()
  if(id){if(!new RegExp(`^${scopeTypes[form.scopeType].prefix}_[a-f0-9]{32}$`).test(id))throw new Error('请填写有效的关联编号');params[form.scopeType]=id}
  if(!metrics){if(form.outcome){if(!Object.hasOwn(outcomes,form.outcome))throw new Error('查询结果筛选无效');params.outcome=form.outcome}
    if(form.traceId.trim()){if(!uuidPattern.test(form.traceId.trim()))throw new Error('请填写有效的追踪编号');params.traceId=form.traceId.trim()}}
  if(form.range){const [from,until]=form.range.map(value=>new Date(value).getTime());if(!Number.isFinite(from)||!Number.isFinite(until)||from>=until)throw new Error('请选择有效的起止时间')
    if(metrics&&(from%60000||until%60000||until-from>1440*60000))throw new Error('计量范围须为完整分钟，且不超过24小时')
    params.from=new Date(from).toISOString();params.until=new Date(until).toISOString()
  }else if(metrics)throw new Error('请选择计量时间范围')
  return params
}
export function assertEvent(event,expected={}){
  if(!event||!uuidPattern.test(event.event_id)||!uuidPattern.test(event.trace_id)||!uuidPattern.test(event.report_id)||!Object.hasOwn(collectionPoints,event.collection_point)||!Object.hasOwn(outcomes,event.outcome)||!Object.hasOwn(reasons,event.reason_code))fail()
  if(Object.keys(event).some(key=>!eventFields.includes(key))||!eventFields.every(key=>Object.hasOwn(event,key))||!/^[A-Za-z0-9_-]{1,64}$/.test(event.collector_id))fail()
  for(const name of ['started_at','completed_at','received_at'])if(!time(event[name]))fail()
  if(Date.parse(event.started_at)>Date.parse(event.completed_at))fail()
  for(const name of ['record_count','byte_count','duration_ms','http_status'])if(!integer(event[name]))fail()
  for(const [name,prefix] of Object.entries({resource_id:'RES',capability_id:'CAP',relationship_id:'REL',subject_id:'SUB',policy_id:'POL'}))if(event[name]!==null&&!new RegExp(`^${prefix}_[a-f0-9]{32}$`).test(event[name]))fail()
  for(const name of ['relationship_version','policy_version'])if(event[name]!==null&&(!integer(event[name])||event[name]===0))fail()
  if(event.http_status<100||event.http_status>599||(event.policy_id===null)!==(event.policy_version===null)||event.relationship_version!==null&&event.relationship_id===null)fail()
  if(event.outcome==='SUCCESS'){
    if(event.reason_code!=='COMPLETED'||!([200,202].includes(event.http_status)))fail()
    if(event.collection_point==='REST_PROVIDER'&&(event.http_status!==200||['relationship_id','capability_id','subject_id','resource_id','policy_id','relationship_version'].some(key=>event[key]===null)))fail()
  }else if(event.outcome==='DENIED'){
    if(![400,401,403].includes(event.http_status)||!['IDENTITY_INVALID','RELATIONSHIP_UNAVAILABLE','SUBJECT_UNAVAILABLE','RELATIONSHIP_INACTIVE','RELATIONSHIP_EXPIRED','POLICY_UNAVAILABLE','FIELDS_FORBIDDEN','TIME_FORBIDDEN','CAPABILITY_UNAVAILABLE','INVALID_REQUEST','OPERATOR_FORBIDDEN'].includes(event.reason_code))fail()
  }else if(!['SOURCE_UNAVAILABLE','CONTROL_UNAVAILABLE','SERIALIZATION_ERROR','INTERNAL_ERROR','KAFKA_UNAVAILABLE'].includes(event.reason_code)||event.http_status<500&&!(event.http_status===200&&event.reason_code==='SERIALIZATION_ERROR'))fail()
  if(event.outcome!=='SUCCESS'&&(event.record_count||event.byte_count))fail()
  if(event.collection_point==='REST_PROVIDER'){if(['topic','topic_partition','topic_offset'].some(key=>event[key]!==null))fail()}
  else{
    if(event.topic!==null&&(typeof event.topic!=='string'||!event.topic||event.topic.length>249))fail()
    for(const name of ['topic_partition','topic_offset'])if(event[name]!==null&&!integer(event[name]))fail()
    if(event.outcome==='SUCCESS'&&(event.record_count!==1||['topic','topic_partition','topic_offset'].some(key=>event[key]===null)))fail()
    if(event.outcome!=='SUCCESS'&&(event.topic_partition!==null||event.topic_offset!==null))fail()
  }
  if(expected.id&&event.event_id!==expected.id||expected.trace&&event.trace_id!==expected.trace)fail()
  if(expected.params){const p=expected.params;for(const [query,key] of Object.entries({collectionPoint:'collection_point',resourceId:'resource_id',capabilityId:'capability_id',relationshipId:'relationship_id',subjectId:'subject_id',outcome:'outcome',traceId:'trace_id'}))if(p[query]&&event[key]!==p[query])fail()
    if(p.from&&Date.parse(event.completed_at)<Date.parse(p.from)||p.until&&Date.parse(event.completed_at)>=Date.parse(p.until))fail()}
  // Only render the documented summary fields; never spread an arbitrary response into a detail view.
  return event
}
export function eventPage(data,params){
  if(!data||!integer(data.total)||!Array.isArray(data.rows)||data.limit!==params.limit||data.offset!==params.offset||data.rows.length>params.limit||data.rows.length>Math.max(0,data.total-params.offset))fail()
  data.rows.forEach(row=>assertEvent(row,{params}));if(new Set(data.rows.map(row=>row.event_id)).size!==data.rows.length)fail()
  return data
}
export function traceEvents(data,id){if(!Array.isArray(data))fail();data.forEach(row=>assertEvent(row,{trace:id}));if(new Set(data.map(row=>row.event_id)).size!==data.length)fail();return [...data].sort((a,b)=>Date.parse(a.started_at)-Date.parse(b.started_at))}
export function metricWindows(data,params){
  if(!Array.isArray(data)||data.length!==(Date.parse(params.until)-Date.parse(params.from))/60000)fail()
  data.forEach((w,i)=>{if(w.collection_point!==params.collectionPoint||!Object.hasOwn(windowStates,w.state)||Date.parse(w.window_start)!==Date.parse(params.from)+i*60000||Date.parse(w.window_until)!==Date.parse(w.window_start)+60000||w.unit!==(params.collectionPoint==='REST_PROVIDER'?'CALL':'MESSAGE'))fail()
    for(const name of ['attempts','calls','messages','successes','denied','errors','records','bytes'])if(w[name]!==null&&!integer(w[name]))fail()
    for(const name of ['reports','late_reports','maximum_report_delay_ms'])if(!integer(w[name]))fail()
    for(const name of ['average_duration_ms','frequency_per_second'])if(w[name]!==null&&(!Number.isFinite(w[name])||w[name]<0))fail()
    const counters=['attempts','successes','denied','errors','records','bytes'],known=w.attempts!==null
    if(counters.some(key=>(w[key]!==null)!==known)||['MISSING','UNKNOWN'].includes(w.state)&&known)fail()
    if(known&&(w.successes+w.denied+w.errors!==w.attempts||w.attempts===0&&(w.records||w.bytes)))fail()
    if(params.collectionPoint==='REST_PROVIDER'&&w.messages!==null||params.collectionPoint==='KAFKA_PRODUCER'&&w.calls!==null)fail()
    const traffic=params.collectionPoint==='REST_PROVIDER'?w.calls:w.messages
    if(traffic!==(params.collectionPoint==='REST_PROVIDER'?w.attempts:w.successes)||w.late_reports>w.reports||w.state==='CLOSED'&&!w.reports)fail()
    if(known?(Math.abs(w.frequency_per_second-traffic/60)>1e-9||w.frequency_per_second===null):w.frequency_per_second!==null||w.average_duration_ms!==null)fail()
  });return data
}
export function metricSummary(windows,point){
  const key=point==='REST_PROVIDER'?'calls':'messages'
  return [['traffic',point==='REST_PROVIDER'?'接口调用次数':'成功消息数',key],['successes','成功次数','successes'],['denied','拒绝次数','denied'],['errors','异常次数','errors'],['records','共享记录数','records'],['bytes','共享字节（B）','bytes']].map(([id,label,field])=>{
    const known=windows.filter(w=>w[field]!==null),partial=known.length<windows.length||windows.some(w=>w.state==='PARTIAL')
    const value=known.length?known.reduce((n,w)=>n+w[field],0):null
    if(value!==null&&!integer(value))fail()
    return {id,label,value,partial:known.length>0&&partial}
  })
}
export function collectorStatus(data){if(!data||typeof data.enabled!=='boolean'||typeof data.storage_ready!=='boolean')fail();for(const name of ['queued_events','staged_reports','pending_reports','quarantined_reports','submitted_events','dropped_events','spool_failures','delivery_failures','rejected_reports','delivered_reports'])if(!integer(data[name]))fail();if(data.last_success_at!==null&&!time(data.last_success_at))fail();return data}
