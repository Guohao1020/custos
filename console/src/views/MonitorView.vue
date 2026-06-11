<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createClient } from '../api/client'
const router = useRouter()
const api = createClient(() => router.push('/login'))
const s = ref<any>(null); let timer: any
async function load() { try { s.value = (await api.get('/monitor/stats')).data } catch {} }
onMounted(() => { load(); timer = setInterval(load, 5000) })
onUnmounted(() => clearInterval(timer))
</script>
<template>
  <h2>实时监控</h2>
  <el-row :gutter="16" v-if="s">
    <el-col :span="6"><el-card>封印态<h1>{{ s.sealed ? 'SEALED' : 'UNSEALED' }}</h1></el-card></el-col>
    <el-col :span="6"><el-card>活跃租约<h1>{{ s.activeLeases }}</h1></el-card></el-col>
    <el-col :span="6"><el-card>资源数<h1>{{ s.resourceCount }}</h1></el-card></el-col>
    <el-col :span="6"><el-card>审计总数<h1>{{ s.auditTotal }}</h1></el-card></el-col>
    <el-col :span="12"><el-card>决策计数<pre>{{ s.decisionCounts }}</pre></el-card></el-col>
    <el-col :span="12"><el-card>近窗拒绝率<h1>{{ (s.denyRateRecent * 100).toFixed(1) }}%</h1></el-card></el-col>
  </el-row>
</template>
