import { fetchDashboardList } from './dashboard.js'

// Real catalogs never independently borrow demo rows for either side of the relationship.
export async function loadCapabilityCatalog(fetchers,{demo=false,getDemoRows,...paging}={}) {
  if(demo) return { demo:true,...Object.fromEntries(['capabilities','resources'].map(key=>
    [key,{rows:getDemoRows(key),complete:true,error:false,notice:''}])) }
  const keys=['capabilities','resources']
  const results=await Promise.allSettled(keys.map(key=>fetchDashboardList(fetchers[key],key==='capabilities'?'capability_id':'resource_id',paging)))
  return {demo:false,...Object.fromEntries(keys.map((key,index)=>[key,results[index].status==='fulfilled'
    ? {...results[index].value,error:false}
    : {rows:[],complete:false,error:true,notice:(key==='capabilities'?'接口能力':'所属资源')+'读取失败，请刷新重试'}]))}
}
export function filterCapabilities(rows,filters={},resources=[]) {
  const terms=(filters.keyword || '').trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  const names=new Map(resources.map(row=>[row.resource_id,row.resource_name]))
  return rows.filter(row=>{
    for(const key of ['resource_id','type','status','health_status']) if(filters[key] && row[key]!==filters[key]) return false
    const text=['capability_id','capability_name','resource_id','resource_name','endpoint','topic','source_table','query_template_code']
      .map(key=>String(row[key] || '')).concat(names.get(row.resource_id) || '').join(' ').toLocaleLowerCase()
    return terms.every(term=>text.includes(term))
  })
}
export function capabilitySaveScope(form,resources,demo) {
  const resource=resources.find(row=>row.resource_id===form.resource_id)
  if(!resource) return '所属资源尚未读取成功，请刷新资源列表后再保存'
  if(resource.status!=='PUBLISHED') return '只能关联已发布资源，请先发布所选资源'
  if((resource._demo===true)!==demo) return '所属资源与当前表单的数据来源不一致，请重新打开表单'
  return ''
}
