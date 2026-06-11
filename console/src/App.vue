<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { clearToken, getToken } from './api/client'
const route = useRoute(); const router = useRouter()
function logout() { clearToken(); router.push('/login') }
</script>
<template>
  <el-container style="height:100vh">
    <el-aside width="200px" v-if="route.path !== '/login'">
      <el-menu :default-active="route.path" router>
        <el-menu-item index="/monitor">实时监控</el-menu-item>
        <el-menu-item index="/audit">审计浏览</el-menu-item>
        <el-menu-item index="/operator">运维动作</el-menu-item>
        <el-menu-item index="/resources">资源配置</el-menu-item>
        <el-menu-item index="/approvals">审批队列</el-menu-item>
      </el-menu>
      <el-button v-if="getToken()" @click="logout" text>退出登录</el-button>
    </el-aside>
    <el-main><router-view /></el-main>
  </el-container>
</template>
