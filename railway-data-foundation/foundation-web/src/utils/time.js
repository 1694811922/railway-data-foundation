// Backend LocalDateTime values are UTC; append an offset before formatting for the browser locale.
export function displayTime(value) {
  if (!value) return '—'
  const text=String(value)
  const date=new Date(/[zZ]|[+-]\d\d:\d\d$/.test(text)?text:`${text.replace(' ','T')}Z`)
  return Number.isNaN(date.getTime())?text:date.toLocaleString('zh-CN',{hour12:false})
}
