const MAX_BYTES=50*1024*1024
const MIME=new Set(['application/json','application/x-ndjson','application/octet-stream','text/plain'])
export async function downloadErrorBody(body){
 if(body instanceof Blob){if(body.size>16384)return null;try{return JSON.parse(await body.text())}catch{return null}}
 return body
}
export function downloadFile(response){
 const h=response.headers,blob=response.data,type=String(h['content-type']||'').split(';')[0].trim().toLowerCase()
 const disposition=String(h['content-disposition']||''),match=/^attachment;\s*filename="(receiver-(?:message-\d+-\d+|export-\d+)\.(?:json|jsonl|txt|bin))"$/i.exec(disposition)
 const declared=h['x-download-bytes'],count=h['x-export-count']
 if(!(blob instanceof Blob)||!MIME.has(type)||!match||!/^\d+$/.test(String(declared))||!/^\d+$/.test(String(count))||Number(count)<1||Number(count)>10000||Number(declared)>MAX_BYTES||blob.size!==Number(declared))throw new Error('下载响应不完整，请重试；未保存文件')
 return {blob,filename:match[1],count:Number(count),bytes:blob.size}
}
export function saveDownload(file){
 const url=URL.createObjectURL(file.blob),link=document.createElement('a');link.href=url;link.download=file.filename;link.style.display='none';document.body.appendChild(link)
 try{link.click()}finally{link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000)}
}
