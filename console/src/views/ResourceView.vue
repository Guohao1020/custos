<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { createClient } from '../api/client'
const router = useRouter()
const api = createClient(() => router.push('/login'))
const names = ref<string[]>([])
// 表单字段对齐后端 ResourceRecord(name,type,dialect,jdbcUrl,adminUsername,adminPassword,roles)。
// roles 必填：默认挂一个 BUILTIN_READONLY 只读角色（与 examples/mcp-custos.sh 真实注册体一致），
// schema 来自表单；creation/revocation 留空由内置适配器按 dialect 生成。
const form = reactive({
  name: '', type: 'db.relational', dialect: 'mysql',
  jdbcUrl: '', adminUsername: '', adminPassword: '', schema: '',
})
async function load() { names.value = (await api.get('/resources')).data }
async function register() {
  const body = {
    name: form.name, type: form.type, dialect: form.dialect,
    jdbcUrl: form.jdbcUrl, adminUsername: form.adminUsername, adminPassword: form.adminPassword,
    roles: [{
      name: 'read-only', kind: 'BUILTIN_READONLY',
      creationStatements: [], revocationStatements: [],
      defaultTtlSeconds: 3600, schema: form.schema || form.name,
    }],
  }
  await api.post('/resources', body)
  form.adminPassword = ''               // 提交后立即清空密码，不回显、不持久化
  load()
}
async function rotate(n: string) { await api.post(`/resources/${n}/rotate-admin`) }
async function remove(n: string) { await api.delete(`/resources/${n}`); load() }
onMounted(load)
</script>
<template>
  <h2>资源配置</h2>
  <el-table :data="names.map(n=>({name:n}))">
    <el-table-column prop="name" label="资源名" />
    <el-table-column label="操作">
      <template #default="{ row }">
        <el-button size="small" @click="rotate(row.name)">轮换 admin</el-button>
        <el-button size="small" type="danger" @click="remove(row.name)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
  <el-card style="margin-top:16px">
    <h3>注册资源</h3>
    <el-input v-model="form.name" placeholder="name" />
    <el-input v-model="form.dialect" placeholder="dialect (mysql/postgresql)" />
    <el-input v-model="form.jdbcUrl" placeholder="jdbcUrl" />
    <el-input v-model="form.adminUsername" placeholder="adminUsername" />
    <el-input v-model="form.adminPassword" type="password" show-password placeholder="adminPassword(高权限,不回显)" />
    <el-input v-model="form.schema" placeholder="schema (只读角色作用库，默认同 name)" />
    <el-button type="primary" @click="register">注册</el-button>
  </el-card>
</template>
