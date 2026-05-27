<template>
  <div class="app-layout">
    <AppTopbar />
    <div class="app-body">
      <AppSidebar />
      <main class="app-main">
        <AppBreadcrumb />
        <div class="app-content"><slot /></div>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import AppTopbar from './AppTopbar.vue'
import AppSidebar from './AppSidebar.vue'
import AppBreadcrumb from './AppBreadcrumb.vue'
</script>

<style scoped>
.app-layout { display: flex; flex-direction: column; height: 100vh; overflow: hidden; }
.app-body { display: flex; flex: 1; overflow: hidden; }
.app-main { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
.app-content { flex: 1; overflow-y: auto; padding: var(--space-6) var(--space-8); max-width: 1440px; width: 100%; margin: 0 auto; }
@media (max-width: 1023px) { .app-content { padding: var(--space-4) var(--space-6); } }
</style>
