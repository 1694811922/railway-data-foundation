import { receivingPage } from './receiving.js'

export function receivingGrant(value) {
  if (value === undefined) return ''
  if (typeof value !== 'string' || !/^KSG_[a-f0-9]{32}$/.test(value)) throw new Error('订阅入口编号无效，请从我的 Kafka 订阅重新进入')
  return value
}

// A failed lookup is never interpreted as "no task". Historical tasks need no
// live Kafka credentials or effective grant; only creating a new one does.
export async function resolveReceivingEntry(grant, { list, subscription, demo = false, current = () => true }) {
  if (demo) return { kind: 'disabled' }
  receivingGrant(grant)
  if (!grant) throw new Error('请选择具体订阅')
  const page = receivingPage(await list({ grantId: grant, limit: 1, offset: 0 }))
  if (!current()) return { kind: 'stale' }
  if (page.total > 1 || page.items.length !== page.total || page.items.some(row => row.grant_id !== grant || !/^RCV_[a-f0-9]{32}$/.test(row.task_id))) throw new Error('接收任务关联响应无效，请重试')
  if (page.total === 1) return { kind: 'existing', task: page.items[0] }
  const row = await subscription(grant)
  if (!current()) return { kind: 'stale' }
  if (row?.grant_id !== grant || row?._demo) throw new Error('订阅响应与当前入口不一致')
  if (!row.broker_access_confirmed) throw new Error('该订阅尚无接收任务，当前授权不可用于创建；请先检查审批及权限状态')
  return { kind: 'create', grant: row }
}
