export function accountPasswordError(password) {
  if (!password || Array.from(password).length < 12 || new TextEncoder().encode(password).length > 72)
    return '密码至少12个字符，且不超过72个UTF-8字节'
  return ''
}
export function accountFormError(form) {
  if (!/^[A-Za-z][A-Za-z0-9_.-]{2,63}$/.test(form.username || ''))
    return '登录账号须为3至64位，以字母开头，可含字母、数字、点、下划线和短横线'
  if (!form.display_name?.trim() || form.display_name.length > 120) return '请输入120字以内的用户名称'
  if (!['ADMIN', 'PROVIDER', 'DEMAND'].includes(form.role)) return '请选择角色'
  if (form.role !== 'ADMIN' && !form.subject_id) return '请选择关联共享主体'
  return accountPasswordError(form.password)
}
// Each reader owns its sequence; changing filters or closing a dialog invalidates in-flight reads.
export function latestAccountReader() {
  let sequence = 0
  return {
    invalidate() { sequence++ },
    async run(fetch, apply, fail, finish) {
      const current = ++sequence
      try { const result = await fetch(); if (current === sequence) apply(result) }
      catch (error) { if (current === sequence) fail(error) }
      finally { if (current === sequence) finish() }
    },
  }
}
