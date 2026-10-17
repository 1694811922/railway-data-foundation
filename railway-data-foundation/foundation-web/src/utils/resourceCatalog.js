import { fetchDashboardList } from './dashboard.js'

export const resourcePageSizes = [10,20,50]
const value = input => typeof input==='string' ? input : ''
export function catalogQuery(query={}) {
  const size=Number(query.pageSize), page=Number(query.page)
  return {
    filters:{ keyword:value(query.q).slice(0,200), source_id:value(query.sourceFilter),
      status:['DRAFT','PUBLISHED','OFFLINE','RETIRED'].includes(query.status) ? query.status : '',
      security_level:['公开','内部','敏感','机密'].includes(query.security) ? query.security : '' },
    page:Number.isSafeInteger(page) && page>0 ? page : 1,
    pageSize:resourcePageSizes.includes(size) ? size : 10,
  }
}
export function catalogRouteQuery(filters,page,pageSize) {
  return { q:filters.keyword.trim() || undefined, sourceFilter:filters.source_id || undefined,
    status:filters.status || undefined, security:filters.security_level || undefined,
    page:page>1 ? String(page) : undefined, pageSize:pageSize!==10 ? String(pageSize) : undefined }
}
export function filterResources(rows,filters={}) {
  const terms=(filters.keyword || '').trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return rows.filter(row=>{
    if(filters.source_id && row.source_id!==filters.source_id) return false
    if(filters.status && row.status!==filters.status) return false
    if(filters.security_level && row.security_level!==filters.security_level) return false
    const text=['resource_name','resource_id','physical_table','source_name','owner_department']
      .map(key=>String(row[key] || '')).join(' ').toLocaleLowerCase()
    return terms.every(term=>text.includes(term))
  })
}
export function catalogPage(rows,page=1,pageSize=10) {
  const safeSize=resourcePageSizes.includes(pageSize) ? pageSize : 10
  const pages=Math.max(1,Math.ceil(rows.length/safeSize))
  const current=Math.min(pages,Math.max(1,Number.isSafeInteger(page) ? page : 1))
  return { page:current, rows:rows.slice((current-1)*safeSize,current*safeSize), pages }
}

// Fallback is allowed only before the first real page resolves, and only while
// the view has never shown real records. Mid-pagination failures stay failures.
export async function loadResourceCatalog(fetchPage,{forceMock=false,allowDemo=true,getDemoRows,canUseDemo, ...paging}={}) {
  const mock=()=>({ rows:getDemoRows(),demo:true,complete:true,notice:'' })
  if(forceMock) return mock()
  let responded=false
  try {
    const result=await fetchDashboardList(async params=>{
      const page=await fetchPage(params)
      responded=true
      return page
    },'resource_id',paging)
    return { ...result, demo:false }
  } catch(error) {
    if(!responded && allowDemo && canUseDemo?.(error)) return mock()
    throw error
  }
}
