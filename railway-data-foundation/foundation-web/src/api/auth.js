import { request } from '../utils/request'
import { session, explicitDemo, acceptUser, clearSession } from '../utils/sessionState'
const demoUser={user_id:'DEMO_ADMIN',username:'demo_admin',display_name:'演示管理员',role:'ADMIN',subject_id:null,subject_name:null,department:'显式演示环境'}
export async function refreshCsrf(){session.csrf=await request.get('/auth/csrf');return session.csrf}
export async function restoreSession(){
  if(session.initialized)return
  if(explicitDemo){session.initialized=true;if(sessionStorage.getItem('foundation_demo_login')==='1')acceptUser(demoUser);return}
  try{acceptUser(await request.get('/auth/me',{quiet:true}));await refreshCsrf()}catch(error){if(error.response?.status===401)clearSession();else throw error}
}
export async function login(username,password){
  if(explicitDemo){sessionStorage.setItem('foundation_demo_login','1');acceptUser(demoUser);return}
  await refreshCsrf()
  const user=await request.post('/auth/login',new URLSearchParams({username,password}),{quiet:true})
  acceptUser(user);await refreshCsrf()
}
export async function logout(){
  if(explicitDemo)sessionStorage.removeItem('foundation_demo_login')
  else await request.post('/auth/logout')
  clearSession()
}
export async function changeOwnPassword(currentPassword,newPassword){
  if(explicitDemo)throw new Error('演示模式不能修改真实登录密码')
  await request.post('/auth/password',{current_password:currentPassword,new_password:newPassword},{quiet:true})
}
