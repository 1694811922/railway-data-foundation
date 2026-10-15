import { accountPasswordError } from './account.js'
export function registrationError(form, bootstrap) {
  if (!/^[A-Za-z][A-Za-z0-9_.-]{2,63}$/.test(form.username || '')) return '账号须为3至64位，以字母开头，可含数字、点、下划线和短横线'
  if (!form.display_name?.trim() || form.display_name.length > 120) return '请输入120字以内的用户名称'
  if (!bootstrap && !['PROVIDER', 'DEMAND'].includes(form.role)) return '请选择数据提供方或数据需求方'
  if (!bootstrap && (!form.organization_name?.trim() || form.organization_name.length > 120)) return '请输入120字以内的单位名称'
  return accountPasswordError(form.password) || (form.password !== form.confirm_password ? '两次输入的密码不一致' : '')
}
export function registrationBody(form, bootstrap) {
  const body = { username: form.username, display_name: form.display_name.trim(), password: form.password }
  if (!bootstrap) Object.assign(body, { role: form.role, organization_name: form.organization_name.trim() })
  return body
}
export function publicAuthRoute(path) { return ['/login', '/register'].includes(path) }
