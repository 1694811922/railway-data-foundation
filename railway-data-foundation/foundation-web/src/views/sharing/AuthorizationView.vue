<script setup>
import {computed,ref} from 'vue'
import {useRoute,useRouter,onBeforeRouteLeave,onBeforeRouteUpdate} from 'vue-router'
import {session} from '../../utils/sessionState'
import {authorizationMethod,authorizationTabQuery} from '../../utils/authorizationNavigation'
import RelationshipView from './RelationshipView.vue'
import SubscriptionView from './SubscriptionView.vue'
const route=useRoute(),router=useRouter(),child=ref(null),method=computed(()=>authorizationMethod(route.query))
const busy=computed(()=>!!child.value?.busy)
function change(value){if(!busy.value&&value!==method.value)router.push({path:'/sharing-authorizations',query:authorizationTabQuery(value)})}
function canLeave(){return !session.user||!busy.value}
onBeforeRouteUpdate(canLeave);onBeforeRouteLeave(canLeave)
</script>
<template><div class="stack"><div class="page-heading"><h1>共享授权</h1></div>
 <el-tabs :model-value="method" :before-leave="()=>!busy" @tab-change="change" data-testid="authorization-tabs"><el-tab-pane name="REST" label="REST接口" :disabled="busy" /><el-tab-pane name="KAFKA" label="Kafka订阅" :disabled="busy" /></el-tabs>
 <RelationshipView v-if="method==='REST'" ref="child" embedded /><SubscriptionView v-else ref="child" embedded />
</div></template>
