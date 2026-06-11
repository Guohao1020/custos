<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { setToken } from '../api/client'
const token = ref(''); const router = useRouter()
function login() { if (token.value.trim()) { setToken(token.value.trim()); router.push('/monitor') } }
</script>
<template>
  <el-card style="max-width:420px;margin:80px auto">
    <h2>Custos 控制台登录</h2>
    <el-input v-model="token" type="password" placeholder="填入 admin token" show-password @keyup.enter="login" />
    <el-button type="primary" style="margin-top:12px" @click="login">登录</el-button>
    <p style="color:#888;font-size:12px">token 仅存于 sessionStorage，刷新即清。</p>
  </el-card>
</template>
