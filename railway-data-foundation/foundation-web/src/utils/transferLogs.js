export const transferEvents={CREATED:'任务创建',START_REQUESTED:'请求启动',RESUME_REQUESTED:'请求恢复',STOP_REQUESTED:'请求停止',CREDENTIALS_UPDATED:'凭据更新',STARTING:'重启恢复',RUNNING:'接收连接就绪',STOPPED:'接收已停止',RETRYING:'连接重试',BLOCKED:'接收阻塞',BATCH_STORED:'批次落库',OFFSET_COMMITTED:'位置提交成功',OFFSET_UNCONFIRMED:'位置提交待确认',REPORT_SENT:'摘要上报成功',REPORT_RETRYING:'摘要等待补报',REPORT_REJECTED:'摘要被拒绝'}
export const transferReasons={AUTHORIZATION_INVALID:'授权、主体或技术凭据已不可用',CONTEXT_INVALID:'主题身份、检查点或消息一致性校验失败',CONTROL_UNAVAILABLE:'底座授权核验暂不可用，已暂停读取',KAFKA_AUTH_FAILED:'Kafka认证或授权失败',OFFSET_EXPIRED:'消费位置已超出保留范围，未跳过历史',RETRIES_EXHAUSTED:'连续恢复失败，需处理后手动恢复',TASK_CREATED:'接收任务已建立',TASK_OPERATION:'用户操作已受理',CREDENTIALS_REPLACED:'已更新接收凭据',RECEIVER_STARTING:'服务重启，等待重新核验',RECEIVER_READY:'已获得分区，等待或接收消息',RECEIVER_STOPPED:'任务已停止，连接已释放',RECEIVER_BLOCKED:'任务阻塞，处理错误后手动恢复',RECEIVER_RETRYING:'连接、落库或提交未确认，等待重试',DATABASE_COMMITTED:'消息与数据库检查点已事务提交',KAFKA_COMMIT_CONFIRMED:'Kafka已确认本批下一消费位置',KAFKA_COMMIT_UNKNOWN:'落库成功，Kafka提交回执未确认；重读将去重',SUMMARY_ACKNOWLEDGED:'底座已确认技术摘要',SUMMARY_REJECTED:'授权或上报凭据不可用，已落库数据保留',SUMMARY_PENDING:'底座暂未确认摘要，等待补报'}
export const logOutcomes={SUCCESS:'成功',INFO:'状态',WARNING:'待处理',ERROR:'异常'}
export const commitStates={CONFIRMED:'已确认',UNCONFIRMED:'回执未确认',UNKNOWN:'未知'}
export const reportStates={UNKNOWN:'未知',PENDING:'待补报',SENT:'已确认',REJECTED:'已拒绝'}
export const logColor=value=>({SUCCESS:'success',INFO:'info',WARNING:'warning',ERROR:'danger'}[value]||'info')
const integer=v=>Number.isSafeInteger(v)&&v>=0
export function transferPage(data,params){
  const fail=()=>{throw new Error('传输日志响应不完整，请重试')}
  if(!data||!integer(data.total)||!Array.isArray(data.items)||data.items.length>params.limit||data.items.length>Math.max(0,data.total-params.offset))fail()
  for(const row of data.items){
    if(!integer(row.log_id)||!/^RCV_[a-f0-9]{32}$/.test(row.task_id)||!/^KSG_[a-f0-9]{32}$/.test(row.grant_id)||!transferEvents[row.event_type]||!logOutcomes[row.outcome]||!transferReasons[row.reason_code]||!Array.isArray(row.positions))fail()
    if(params.grantId&&params.grantId!==row.grant_id||params.taskId&&params.taskId!==row.task_id||params.eventType&&params.eventType!==row.event_type||params.outcome&&params.outcome!==row.outcome)fail()
    if(row.storage_duration_ms!=null&&!integer(row.storage_duration_ms))fail()
    for(const key of ['record_count','inserted_count','duplicate_count','byte_count'])if(row[key]!==null&&!integer(row[key]))fail()
    if(row.event_type==='BATCH_STORED'&&(row.record_count===null||row.inserted_count+row.duplicate_count!==row.record_count||!row.positions.length))fail()
    for(const p of row.positions){if(!integer(p.partition)||!integer(p.next_offset))fail();if(p.first_offset!=null&&(!integer(p.first_offset)||!integer(p.last_offset)||p.last_offset<p.first_offset||p.next_offset<=p.last_offset))fail()}
    if(new Set(row.positions.map(p=>p.partition)).size!==row.positions.length||row.commit_state!==null&&!commitStates[row.commit_state]||row.report_state!==null&&!reportStates[row.report_state])fail()
    // Only explicitly documented technical fields may be rendered by components.
    for(const key of ['password','token','encrypted_secrets','message_value','context_json'])if(Object.hasOwn(row,key))fail()
  }
  if(new Set(data.items.map(r=>r.log_id)).size!==data.items.length)fail()
  return data
}
export function logQuery(form){
  const result={}
  if(form.grantId){if(!/^KSG_[a-f0-9]{32}$/.test(form.grantId))throw new Error('请填写完整授权编号');result.grantId=form.grantId}
  if(form.taskId){if(!/^RCV_[a-f0-9]{32}$/.test(form.taskId))throw new Error('请填写完整接收任务编号');result.taskId=form.taskId}
  if(form.eventType)result.eventType=form.eventType
  if(form.outcome)result.outcome=form.outcome
  if(form.range?.length){const [a,b]=form.range.map(v=>new Date(v).getTime());if(!Number.isFinite(a)||!Number.isFinite(b)||a>=b)throw new Error('请选择有效起止时间');result.from=new Date(a).toISOString();result.until=new Date(b).toISOString()}
  return result
}

export function monitorPage(data,params){
  if(!data||typeof data.configured!=='boolean'||data.source!=='RECEIVER_SELF_REPORTED'||data.last_received_at!==null&&!Number.isFinite(Date.parse(data.last_received_at)))throw new Error('管理员监测响应不完整，请重试')
  return transferPage(data,params)
}
