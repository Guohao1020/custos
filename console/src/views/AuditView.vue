<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createClient } from '../api/client'
const router = useRouter()
const api = createClient(() => router.push('/login'))
const rows = ref<any[]>([]); const total = ref(0); const page = ref(0); const size = 20
const agent = ref(''); const decision = ref(''); const chain = ref<any>(null)
async function load() {
  const params: any = { page: page.value, size }
  if (agent.value) params.agent = agent.value
  if (decision.value) params.decision = decision.value
  const r = (await api.get('/audit', { params })).data
  rows.value = r.rows; total.value = r.total
}
async function verify() { chain.value = (await api.get('/audit/verify')).data }
onMounted(() => { load(); verify() })
</script>
<template>
  <h2>审计浏览
    <el-tag v-if="chain" :type="chain.ok ? 'success' : 'danger'">
      {{ chain.ok ? '链完整' : '断链 @seq=' + chain.brokenAtSeq }}
    </el-tag>
  </h2>
  <el-input v-model="agent" placeholder="agent 过滤" style="width:200px" @keyup.enter="page=0;load()" />
  <el-input v-model="decision" placeholder="decision 过滤" style="width:200px" @keyup.enter="page=0;load()" />
  <el-button @click="page=0;load()">查询</el-button>
  <el-table :data="rows" style="margin-top:12px">
    <el-table-column prop="seq" label="seq" width="80" />
    <el-table-column prop="ts" label="时间" />
    <el-table-column prop="actor" label="actor" />
    <el-table-column prop="decision" label="决策" />
    <el-table-column prop="resource" label="资源" />
    <el-table-column prop="resultDigest" label="result" />
  </el-table>
  <el-pagination layout="prev, pager, next" :total="total" :page-size="size"
    @current-change="(p:number)=>{page=p-1;load()}" style="margin-top:12px" />
</template>
