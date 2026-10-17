export const roleNames = { ADMIN: '管理员', PROVIDER: '数据提供方', DEMAND: '数据需求方' }
const admin = [['/dashboard','首页'],['接入管理',[['/sources','数据源管理'],['/scans','元数据扫描']]],['/resources','资源目录'],['/capabilities','接口能力'],['共享治理',[['/sharing-authorizations','共享授权'],['/credentials','REST调用凭据']]],['系统管理',[['/subjects','共享主体管理'],['/accounts','用户账号']]],['运行监管',[['/transfer-monitor','传输监测']]],['/about','系统说明']]
export const menus = {
  ADMIN: admin,
  PROVIDER: [['/dashboard','首页'],['/sources','我的数据源'],['/scans','元数据扫描'],['/resources','我的数据资源'],['/capabilities','我的接口能力'],['/production','数据发送'],['共享申请与记录',[['/relationships','REST 共享记录'],['/kafka-subscriptions','Kafka 订阅记录']]],['运行情况',[['/runtime-events','调用记录'],['/runtime-traces','调用追踪'],['/metrics','共享计量']]],['/about','系统说明']],
  DEMAND: [['/dashboard','首页'],['/resources','资源目录'],['/relationships','我的共享申请'],['/kafka-subscriptions','我的 Kafka 订阅'],['/receiving','数据接收'],['/credentials','我的REST调用凭据'],['/about','系统说明']],
}
export function allowedRoute(role,path) {
  if(role==='ADMIN'&&['/relationships','/kafka-subscriptions','/policies','/access-check'].includes(path))return true
  if(role==='ADMIN'&&['/runtime-events','/runtime-traces','/metrics','/kafka-monitor'].some(p=>path===p||path.startsWith(p+'/')))return true
  const normalized=path.startsWith('/resources/')?'/resources':path.startsWith('/runtime-traces/')?'/runtime-traces':path
  return (menus[role]||[]).flatMap(([key,label])=>Array.isArray(label)?label.map(([p])=>p):[key]).includes(normalized)
}
export function allowedActions(role,actions,kind='sharing') {
  if(role==='ADMIN')return actions.filter(action=>!action.startsWith('provider-'))
  if(role==='PROVIDER')return kind==='sharing'?actions.filter(action=>['provider-confirm','provider-return'].includes(action)):[]
  if(role!=='DEMAND')return []
  return actions.filter(action=>(kind==='execution'?['stop','credential']:['submit','revoke']).includes(action))
}
