import { parseUtcTime } from './time.js'
import { capabilityActionReason } from './capabilityWorkflow.js'

export const dashboardWorkGroups = [
  { key:'resources', label:'待发布资源', id:'resource_id', status:'DRAFT', empty:'当前没有待发布的草稿资源', note:'人工确认业务信息和字段后，在资源详情中发布。' },
  { key:'capabilities', label:'待验证能力', id:'capability_id', status:'DRAFT', empty:'当前没有待验证的草稿能力', note:'仅汇总草稿；验证通过不代表已启用，未接入适配器的类型仅可登记。' },
  { key:'tasks', label:'失败扫描', id:'task_id', status:'FAILED', empty:'当前没有失败的扫描任务', note:'检查失败原因及数据源配置，再由用户主动发起新扫描；不自动重试。' },
]

// Read-only projections of the existing Dashboard lists, not persisted workflow tasks.
export function dashboardWork(data,key,demo=false) {
  const group=dashboardWorkGroups.find(item=>item.key===key)
  if(!group) throw new Error('待处理分类不正确')
  const dataset=data[key]
  const unavailable=(state,notice)=>({...group,rows:[],count:null,partial:false,state,notice})
  if(!dataset) return unavailable('loading','正在读取相关元数据')
  if(dataset.error) return unavailable('error',dataset.notice || '相关元数据读取失败，请刷新重试')
  if(!Array.isArray(dataset.rows) || dataset.rows.some(row=>!row || typeof row[group.id]!=='string' || !row[group.id]
    || (row._demo===true)!==demo)) return unavailable('error','待处理数据结构或来源不一致，请刷新重试')
  const rows=dataset.rows.filter(row=>row.status===group.status).map(row=>{
    const id=row[group.id], time=row.updated_at || row.finished_at || row.started_at || row.created_at
    let name,related,reason,action,route
    if(key==='resources') {
      name=row.resource_name || id;related=row.source_name || row.source_id || '来源未标注'
      reason='确认业务信息、责任单位、安全等级及字段含义后发布'
      action='查看资源';route={path:'/resources/'+encodeURIComponent(id)}
    } else if(key==='capabilities') {
      name=row.capability_name || id;related=row.resource_name || row.resource_id || '所属资源未标注'
      reason=capabilityActionReason(row,'verify',demo) || (demo?'可模拟验证，结果仅用于演示':'检查已有访问配置，再主动验证')
      action='管理资源能力';route=typeof row.resource_id==='string' && row.resource_id
        ? {path:'/capabilities',query:{resource:row.resource_id}} : null
      if(!route)reason='缺少所属资源编号，请刷新确认'
    } else {
      name=row.source_name || row.source_id || id;related='扫描任务'
      reason=row.failure_reason || '失败原因未返回，请检查数据源连接与读取权限'
      action='查看失败任务';route={path:'/scans',query:{task:id}}
    }
    return {id,name,related,reason,action,route,time,status:row.status,type:row.type}
  }).sort((a,b)=>(parseUtcTime(b.time)?.getTime() || 0)-(parseUtcTime(a.time)?.getTime() || 0) || b.id.localeCompare(a.id))
  return {...group,rows,count:rows.length,partial:!dataset.complete,state:'ready',notice:dataset.complete?'':dataset.notice || '仅涵盖本次已读取的元数据'}
}

export function filterDashboardWork(rows,keyword='') {
  const terms=keyword.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return rows.filter(row=>terms.every(term=>[row.id,row.name,row.related,row.reason].join(' ').toLocaleLowerCase().includes(term)))
}
