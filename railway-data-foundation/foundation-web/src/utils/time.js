// Backend LocalDateTime values are UTC; append an offset before formatting for the browser locale.
export function parseUtcTime(value) {
  if (!value) return null
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value
  const text=String(value)
  const date=new Date(/(?:[zZ]|[+-]\d\d:\d\d)$/.test(text)?text:`${text.replace(' ','T')}Z`)
  return Number.isNaN(date.getTime()) ? null : date
}
export function displayTime(value) {
  if (!value) return '—'
  return parseUtcTime(value)?.toLocaleString('zh-CN',{hour12:false}) || String(value)
}
export function beijingDay(value) {
  const date = parseUtcTime(value)
  if (!date) return null
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone:'Asia/Shanghai', year:'numeric', month:'2-digit', day:'2-digit',
  }).formatToParts(date).map(part => [part.type, part.value]))
  return `${parts.year}-${parts.month}-${parts.day}`
}
