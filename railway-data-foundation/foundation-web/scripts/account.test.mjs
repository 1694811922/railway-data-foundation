import test from 'node:test'
import assert from 'node:assert/strict'
import { accountFormError, accountPasswordError, latestAccountReader } from '../src/utils/account.js'

test('账号登记校验角色主体、账号格式及Unicode密码边界', () => {
  const good = { username: 'test_provider', display_name: '提供方管理员', role: 'PROVIDER', subject_id: 'SUB_test', password: 'RandomSecret12345' }
  assert.equal(accountFormError(good), '')
  assert.match(accountFormError({ ...good, username: '1invalid' }), /登录账号/)
  assert.match(accountFormError({ ...good, subject_id: null }), /主体/)
  assert.match(accountFormError({ ...good, role: 'unknown' }), /角色/)
  assert.match(accountFormError({ ...good, display_name: '  ' }), /名称/)
  assert.equal(accountFormError({ ...good, role: 'ADMIN', subject_id: null }), '')
  assert.match(accountPasswordError('😀'.repeat(6)), /至少12/)
  assert.equal(accountPasswordError('汉'.repeat(24)), '')
  assert.match(accountPasswordError('汉'.repeat(25)), /72/)
  assert.equal(accountPasswordError('a'.repeat(72)), '')
  assert.match(accountPasswordError('a'.repeat(73)), /72/)
})
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return { promise, resolve, reject } }
test('快速换页仅显示最新响应，过期错误及完成回调也隔离', async () => {
  const reader=latestAccountReader(), old=deferred(), next=deferred(), events=[]
  const run = task => reader.run(() => task.promise, v => events.push(v), e => events.push(e.message), () => events.push('done'))
  const first=run(old), second=run(next)
  next.resolve('第二页'); await second
  old.reject(new Error('过期错误')); await first
  assert.deepEqual(events, ['第二页', 'done'])
})
test('关闭或切换角色丢弃旧主体；当前失败允许下一次读取成功', async () => {
  const reader=latestAccountReader(), old=deferred(), events=[]
  const first=reader.run(() => old.promise, v => events.push(v), e => events.push(e.message), () => events.push('done'))
  reader.invalidate(); old.resolve('旧提供方'); await first; assert.deepEqual(events, [])
  await reader.run(() => Promise.reject(new Error('读取失败')), v => events.push(v), e => events.push(e.message), () => events.push('done'))
  await reader.run(() => Promise.resolve('新需求方'), v => events.push(v), e => events.push(e.message), () => events.push('done'))
  assert.deepEqual(events, ['读取失败','done','新需求方','done'])
})
