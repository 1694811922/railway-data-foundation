import test from 'node:test'
import assert from 'node:assert/strict'
import { registrationError, registrationBody, publicAuthRoute } from '../src/utils/registration.js'
const form = { username:'new_user', display_name:' 新用户 ', role:'DEMAND', organization_name:' 测试单位 ', password:'a'.repeat(12), confirm_password:'a'.repeat(12) }
test('registration validates identity, role, unit and Unicode passwords',()=>{
  assert.equal(registrationError(form,false),'')
  for(const patch of [{role:'ADMIN'},{organization_name:''},{username:'1bad'},{password:'a'.repeat(11)},{confirm_password:'different'}])assert.ok(registrationError({...form,...patch},false))
  assert.equal(registrationError({...form,password:'中'.repeat(24),confirm_password:'中'.repeat(24)},false),'')
  assert.ok(registrationError({...form,password:'中'.repeat(25),confirm_password:'中'.repeat(25)},false))
  assert.equal(registrationError({...form,role:'ADMIN',organization_name:''},true),'')
})
test('bootstrap payload cannot carry a caller-controlled role or subject',()=>{
  assert.deepEqual(registrationBody({...form,subject_id:'not_trusted'},true),{username:'new_user',display_name:'新用户',password:form.password})
  assert.deepEqual(registrationBody(form,false),{username:'new_user',display_name:'新用户',password:form.password,role:'DEMAND',organization_name:'测试单位'})
  assert.equal('confirm_password' in registrationBody(form,false),false)
})
test('only login and registration are public frontend routes',()=>{
  assert.equal(publicAuthRoute('/register'),true);assert.equal(publicAuthRoute('/login'),true)
  for(const path of ['/accounts','/registrations','/register/admin','/sources'])assert.equal(publicAuthRoute(path),false)
})
