<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createClient } from '../api/client'
const router = useRouter()
const api = createClient(() => router.push('/login'))
const rows = ref<any[]>([])
async function load() { rows.value = (await api.get('/approvals')).data }
async function approve(id: string) { await api.post(`/approvals/${id}/approve`); load() }
async function deny(id: string) { await api.post(`/approvals/${id}/deny`); load() }
onMounted(load)
</script>
<template>
  <h2>审批队列</h2>
  <el-table :data="rows">
    <el-table-column prop="id" label="id" />
    <el-table-column prop="agent" label="agent" />
    <el-table-column prop="tool" label="工具" />
    <el-table-column prop="resource" label="资源" />
    <el-table-column prop="risk" label="风险" />
    <el-table-column prop="reason" label="原因" />
    <el-table-column label="操作">
      <template #default="{ row }">
        <el-button size="small" type="success" @click="approve(row.id)">批准</el-button>
        <el-button size="small" type="danger" @click="deny(row.id)">拒绝</el-button>
      </template>
    </el-table-column>
  </el-table>
</template>
