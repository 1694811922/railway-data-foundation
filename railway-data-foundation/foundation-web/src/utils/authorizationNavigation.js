// Legacy admin bookmarks resolve to one workspace; other roles keep their own routes.
export function authorizationRedirect(role,path,query={}){
 if(role!=='ADMIN')return null
 const method=path==='/kafka-subscriptions'?'KAFKA':['/relationships','/policies','/access-check'].includes(path)?'REST':null
 if(!method)return null
 return {path:'/sharing-authorizations',query:{...query,method,...(path==='/policies'?{section:'policy'}:path==='/access-check'?{section:'test'}:{})},replace:true}
}
export const authorizationMethod=query=>query?.method==='KAFKA'?'KAFKA':'REST'
export function authorizationTabQuery(method){return {method:method==='KAFKA'?'KAFKA':'REST'}}
