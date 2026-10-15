<script setup>
import { session } from '../../utils/sessionState'
import DashboardView from './DashboardView.vue'
import DemandHome from './DemandHome.vue'
</script>
<template><DemandHome v-if="session.user?.role==='DEMAND'" /><DashboardView v-else /></template>
