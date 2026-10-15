// Only used by explicit VITE_USE_MOCK=true. Never used after a real API failure.
// Browser fixtures simulate business parties; these are not authenticated accounts.
const key='foundation_subject_demo_v1'
const now=()=>new Date().toISOString()
export const subjectSeed=[
  {subject_id:'SUB_DEMO_PORT',subject_code:'GUANGZHOU_PORT',subject_name:'广州港数据提供方（示例）',subject_type:'PROVIDER',owner_department:'广州港数据管理部',description:'模拟提供方，不代表已获得源系统授权'},
  {subject_id:'SUB_DEMO_RAILWAY',subject_code:'RAILWAY_RESEARCH',subject_name:'铁路科研需求方（示例）',subject_type:'DEMAND',owner_department:'铁路科研项目组',description:'模拟需求方，不代表真实运行时身份'},
].map(row=>({...row,status:'ENABLED',version:1,created_at:now(),updated_at:now(),_demo:true}))
function read() {
  const saved=localStorage.getItem(key)
  return saved ? JSON.parse(saved) : structuredClone(subjectSeed)
}
function write(rows) { localStorage.setItem(key,JSON.stringify(rows)) }
function change(id,body,edit) {
  const rows=read(),index=rows.findIndex(row=>row.subject_id===id),row=rows[index]
  if (!row) throw new Error('演示主体不存在')
  if (row.version!==body.version) throw new Error('主体信息已变化，请刷新后重新操作')
  if (!edit && row.status===body.status) return structuredClone(row)
  rows[index]={...row,...body,version:row.version+1,updated_at:now()};write(rows)
  return structuredClone(rows[index])
}
export const subjectDemoApi={
  list:async ({limit=200,offset=0}={})=>read().sort((a,b)=>b.updated_at.localeCompare(a.updated_at)||b.subject_id.localeCompare(a.subject_id)).slice(offset,offset+limit),
  create:async body=>{
    const rows=read()
    if (rows.some(row=>row.subject_code===body.subject_code)) throw new Error('业务编码已存在')
    const row={...body,subject_id:'SUB_DEMO_'+crypto.randomUUID().replaceAll('-','').toUpperCase(),status:'ENABLED',version:1,created_at:now(),updated_at:now(),_demo:true}
    rows.push(row);write(rows);return structuredClone(row)
  },
  update:async (id,body)=>change(id,body,true),
  status:async (id,body)=>change(id,body,false),
  delete:async id=>{
    const rows=read(),index=rows.findIndex(row=>row.subject_id===id)
    if(index<0)throw new Error('演示主体不存在')
    if(rows[index].status!=='DISABLED')throw new Error('请先停用共享主体，再执行删除')
    rows.splice(index,1);write(rows)
  },
}
