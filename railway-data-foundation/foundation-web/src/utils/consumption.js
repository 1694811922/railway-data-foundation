export function assertConsumptionPage(data, id, limit = 20, offset = 0) {
  const summary = data?.summary
  if (!data || data._demo || data.grant_id !== id || !Array.isArray(data.items) || !Number.isSafeInteger(data.total) || data.total < 0 || data.items.length > limit || (data.items.length && data.total < offset + data.items.length)
    || !summary || summary.reports !== data.total || !['NOT_REPORTED', 'OBSERVED'].includes(summary.state)) throw new Error('消费摘要响应与当前授权不一致')
  if (!data.total) {
    if (summary.state !== 'NOT_REPORTED' || summary.records !== null || summary.bytes !== null || summary.last_received_at !== null) throw new Error('未上报不能显示为已消费零条')
  } else if (summary.state !== 'OBSERVED' || !Number.isSafeInteger(summary.records) || summary.records < 0 || !Number.isSafeInteger(summary.bytes) || summary.bytes < 0 || !Number.isFinite(Date.parse(summary.last_received_at))) throw new Error('消费观察统计不合法')
  if (new Set(data.items.map(row => row.report_id)).size !== data.items.length) throw new Error('消费报告重复')
  for (const row of data.items) {
    if (!/^[a-f0-9-]{36}$/.test(row.report_id || '') || !Number.isInteger(row.record_count) || row.record_count < 0 || row.record_count > 100 || !Number.isSafeInteger(row.byte_count) || row.byte_count < 0 || !Number.isInteger(row.grant_version) || row.grant_version < 1
      || ![row.started_at, row.completed_at, row.received_at].every(value => Number.isFinite(Date.parse(value))) || ['password', 'consumption_token', 'content_digest', 'value'].some(key => Object.hasOwn(row, key))) throw new Error('消费报告内容不合法')
  }
  return data
}
