export function passwordChangeError(form) {
  if(!form.currentPassword)return '请输入当前密码'
  if(new TextEncoder().encode(form.currentPassword).length>72)return '当前密码不超过72个UTF-8字节'
  if(!form.newPassword||Array.from(form.newPassword).length<12||new TextEncoder().encode(form.newPassword).length>72)
    return '新密码至少12个字符，且不超过72个UTF-8字节'
  if(form.newPassword===form.currentPassword)return '新密码不能与当前密码相同'
  if(form.newPassword!==form.confirmPassword)return '两次输入的新密码不一致'
  return ''
}
