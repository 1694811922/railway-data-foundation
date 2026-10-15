export const subjectTypes = { PROVIDER:'提供方', DEMAND:'需求方' }
export const subjectStatuses = { ENABLED:'已启用', DISABLED:'已停用' }

export function subjectPayload(form, editing=false) {
  const text = key => String(form[key] ?? '').trim()
  const subject_name=text('subject_name'), owner_department=text('owner_department'), description=text('description')
  if (!subject_name || subject_name.length>120) throw new Error('主体名称必填，最多120字')
  if (!owner_department || owner_department.length>120) throw new Error('责任单位必填，最多120字')
  if (description.length>1000) throw new Error('业务说明最多1000字')
  const body={subject_name,owner_department,description}
  if (editing) {
    if (!Number.isInteger(form.version) || form.version<1) throw new Error('缺少有效版本，请刷新后重试')
    return {...body,version:form.version}
  }
  const subject_code=text('subject_code'), subject_type=text('subject_type')
  if (!/^[A-Z][A-Z0-9_]{2,63}$/.test(subject_code)) throw new Error('业务编码使用3–64位大写字母、数字或下划线，以字母开头')
  if (!Object.hasOwn(subjectTypes,subject_type)) throw new Error('请选择提供方或需求方')
  return {...body,subject_code,subject_type}
}

export function assertSubject(row, demo=false, expected={}) {
  if (!row || Boolean(row._demo)!==demo || typeof row.subject_id!=='string'
      || !(demo ? /^SUB_DEMO_[A-Z0-9_]+$/ : /^SUB_[a-f0-9]{32}$/).test(row.subject_id)
      || !/^[A-Z][A-Z0-9_]{2,63}$/.test(row.subject_code || '')
      || !Object.hasOwn(subjectTypes,row.subject_type) || !Object.hasOwn(subjectStatuses,row.status)
      || typeof row.subject_name!=='string' || !row.subject_name.trim() || row.subject_name.length>120
      || typeof row.owner_department!=='string' || !row.owner_department.trim() || row.owner_department.length>120
      || !Number.isInteger(row.version) || row.version<1)
    throw new Error('共享主体响应格式或数据模式不正确，请刷新检查')
  for (const key of ['subject_id','subject_code','subject_type','version','status'])
    if (expected[key]!==undefined && row[key]!==expected[key]) throw new Error('主体响应与当前操作不匹配，请刷新检查结果')
  return row
}

export function filterSubjects(rows,{keyword='',type='',status=''}={}) {
  const query=keyword.trim().toLowerCase()
  return rows.filter(row=>(!type || row.subject_type===type) && (!status || row.status===status)
    && (!query || ['subject_id','subject_code','subject_name','owner_department'].some(key=>String(row[key]||'').toLowerCase().includes(query))))
}
