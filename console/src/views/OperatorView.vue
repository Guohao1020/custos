<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createClient } from '../api/client'
const router = useRouter()
const api = createClient(() => router.push('/login'))
const status = ref<any>(null); const share = ref('')
async function load() { status.value = (await api.get('/operator/status')).data }
// body 字段名 share：与 OperatorController.unseal(@RequestBody Map<String,String>).get("share") 对齐
async function unseal() { status.value = (await api.post('/operator/unseal', { share: share.value })).data; share.value = '' }
async function seal() { await api.post('/operator/seal'); load() }
onMounted(load)
</script>
<template>
  <h2>运维动作</h2>
  <el-card v-if="status">
    <p>封印态：{{ status.sealed ? 'SEALED' : 'UNSEALED' }} · 进度 {{ status.progress }}/{{ status.threshold }}</p>
    <el-input v-model="share" placeholder="提交一片解封分片(base64)" style="width:420px" />
    <el-button type="primary" @click="unseal">提交分片</el-button>
    <el-button @click="seal" :disabled="status.sealed">密封</el-button>
    <p style="color:#888">轮换主密钥：REST 未备，roadmap。</p>
  </el-card>
</template>
