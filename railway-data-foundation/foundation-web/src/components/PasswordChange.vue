<script setup>
import { ref, reactive, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { changeOwnPassword } from '../api/auth'
import { clearSession, explicitDemo } from '../utils/sessionState'
import { passwordChangeError } from '../utils/passwordChange.js'
const router=useRouter(),visible=ref(false),busy=ref(false),error=ref('')
const form=reactive({currentPassword:'',newPassword:'',confirmPassword:''})
let active=true
function clear(){Object.assign(form,{currentPassword:'',newPassword:'',confirmPassword:''});error.value=''}
function open(){if(explicitDemo)return;clear();visible.value=true}
async function save(){
  if(busy.value)return
  error.value=passwordChangeError(form);if(error.value)return
  busy.value=true
  try{
    await changeOwnPassword(form.currentPassword,form.newPassword)
    if(active){clear();ElMessage.success('登录密码已修改，请重新登录');clearSession();router.replace('/login')}
  }catch(e){
    if(active){error.value=e.response?.data?.message||'修改结果未确认，请重新登录核对';Object.assign(form,{currentPassword:'',newPassword:'',confirmPassword:''})}
  }finally{busy.value=false}
}
onBeforeUnmount(()=>{active=false;clear()})
</script>
<template>
  <el-button text size="small" :disabled="explicitDemo" @click="open">修改密码</el-button>
  <el-dialog v-model="visible" title="修改登录密码" width="480px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @closed="clear">
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="当前密码"><el-input v-model="form.currentPassword" type="password" autocomplete="current-password" maxlength="72" :disabled="busy" /></el-form-item>
      <el-form-item label="新密码"><el-input v-model="form.newPassword" type="password" autocomplete="new-password" maxlength="72" :disabled="busy" /></el-form-item>
      <el-form-item label="确认新密码"><el-input v-model="form.confirmPassword" type="password" autocomplete="new-password" maxlength="72" :disabled="busy" /></el-form-item>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p class="password-hint">至少12个字符、不超过72个UTF-8字节；修改后需重新登录。</p>
    </el-form>
    <template #footer><el-button :disabled="busy" @click="visible=false">取消</el-button><el-button type="primary" :loading="busy" @click="save">保存新密码</el-button></template>
  </el-dialog>
</template>
<style scoped>.password-hint{font-size:13px;color:#667b8b;margin-bottom:0}</style>
