<script setup>
import { session } from '../../utils/sessionState'
import ResourceDetailView from './ResourceDetailView.vue'
import PublicResourceDetail from './PublicResourceDetail.vue'
</script>
<template><PublicResourceDetail v-if="session.user?.role==='DEMAND'" /><ResourceDetailView v-else /></template>
