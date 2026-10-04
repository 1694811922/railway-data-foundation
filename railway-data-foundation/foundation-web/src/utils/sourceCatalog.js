import { fetchDashboardList } from './dashboard.js'

export async function loadSourceCatalog(fetchPage,{demo=false,getDemoRows,...paging}={}) {
  if(demo)return {rows:getDemoRows(),demo:true,error:false,complete:true,notice:''}
  try{return {...await fetchDashboardList(fetchPage,'source_id',paging),demo:false,error:false}}
  catch{return {rows:[],demo:false,error:true,complete:false,notice:'数据源列表读取失败，请刷新重试'}}
}
export function filterSources(rows,{keyword='',source_type='',status=''}={}) {
  const terms=keyword.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return rows.filter(row=>(!source_type||row.source_type===source_type)&&(!status||row.status===status)
    &&terms.every(term=>['source_id','source_name','source_type','host','port','database_name','description']
      .map(key=>String(row[key]??'')).join(' ').toLocaleLowerCase().includes(term)))
}
export function validateSource(form,id) {
  if(!form.source_name?.trim()||!form.host?.trim()||!form.database_name?.trim()||!form.username?.trim())
    return '请填写数据源名称、数据库地址、数据库名称和用户名'
  if(form.source_type!=='MYSQL')return '当前只支持接入MySQL数据库'
  if(!/^[A-Za-z0-9][A-Za-z0-9.-]{0,252}$/.test(form.host.trim()))return '请输入合法的 IPv4 地址或域名，不要填写完整连接地址'
  if(!/^[A-Za-z0-9_]{1,64}$/.test(form.database_name.trim()))return '数据库名称仅支持字母、数字和下划线'
  if(!Number.isInteger(Number(form.port))||Number(form.port)<1||Number(form.port)>65535)return '端口必须为 1–65535 范围内的整数'
  if(!form.password&&(!id||form.connection_mode==='ENV_REFERENCE'))return '新增或转换数据源时请输入数据库密码'
  if(form.source_name.trim().length>120||form.username.trim().length>128||(form.password||'').length>1024||(form.description||'').length>1000)
    return '填写内容超过字段长度限制，请缩短后保存'
  if(!['ENABLED','DISABLED'].includes(form.status)||!['PREFERRED','REQUIRED','VERIFY_CA','VERIFY_IDENTITY','DISABLED'].includes(form.ssl_mode))
    return '数据源状态或加密连接方式不合法'
  return ''
}
export function sourceSaveScope(id,rows,demo) {
  if(!id)return ''
  const row=rows.find(row=>row.source_id===id)
  if(!row)return '当前数据源已不在读取列表中，请刷新并重新打开编辑表单'
  return (row._demo===true)===demo?'':'数据源与当前表单模式不一致，请重新打开表单'
}
