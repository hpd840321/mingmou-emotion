<template>
  <div class="not-found">
    <h1>404</h1>
    <p>页面未找到</p>
    <el-button type="primary" @click="$router.push('/')">返回首页</el-button>
  </div>
</template>

<style scoped>
.not-found { display: flex; align-items: center; justify-content: center; height: 60vh; flex-direction: column; gap: 16px; }
.not-found h1 { font-size: 48px; color: var(--color-muted-fg); }
</style>
