import test from 'node:test'
import assert from 'node:assert/strict'
import { passwordChangeError } from '../src/utils/passwordChange.js'
test('修改本人密码校验当前密码、长度、相同密码和二次确认',()=>{
  const good={currentPassword:'OriginalSecret123',newPassword:'ReplacementSecret123',confirmPassword:'ReplacementSecret123'}
  assert.equal(passwordChangeError(good),'')
  assert.match(passwordChangeError({...good,currentPassword:''}),/当前密码/)
  assert.match(passwordChangeError({...good,newPassword:'short'}),/至少12/)
  assert.match(passwordChangeError({...good,newPassword:'😀'.repeat(6)}),/至少12/)
  assert.match(passwordChangeError({...good,newPassword:good.currentPassword}),/相同/)
  assert.match(passwordChangeError({...good,confirmPassword:'different'}),/不一致/)
})
test('按UTF-8字节限制密码，不截断多字节密码',()=>{
  const value='汉'.repeat(24)
  assert.equal(passwordChangeError({currentPassword:'OriginalSecret123',newPassword:value,confirmPassword:value}),'')
  assert.match(passwordChangeError({currentPassword:'OriginalSecret123',newPassword:value+'汉',confirmPassword:value+'汉'}),/72/)
  assert.match(passwordChangeError({currentPassword:'汉'.repeat(25),newPassword:value,confirmPassword:value}),/当前密码/)
})
