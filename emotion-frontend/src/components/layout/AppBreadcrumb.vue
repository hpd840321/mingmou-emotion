<template>
  <div class="breadcrumb" v-if="nav.breadcrumbs.length > 0">
    <template v-for="(item, i) in nav.breadcrumbs" :key="i">
      <span v-if="i > 0" class="breadcrumb-sep">›</span>
      <router-link v-if="item.to && i < nav.breadcrumbs.length - 1" :to="item.to" class="breadcrumb-link">{{ item.label }}</router-link>
      <span v-else class="breadcrumb-current">{{ item.label }}</span>
    </template>
  </div>
</template>

<script setup lang="ts">
import { useNavigationStore } from '@/stores/useNavigationStore'
const nav = useNavigationStore()
</script>

<style scoped>
.breadcrumb { height: var(--breadcrumb-height); display: flex; align-items: center; padding: 0 var(--space-8); font-size: var(--text-xs); flex-shrink: 0; }
.breadcrumb-sep { color: #94A3B8; margin: 0 var(--space-2); }
.breadcrumb-link { color: var(--color-muted-fg); text-decoration: none; }
.breadcrumb-link:hover { color: var(--color-secondary); text-decoration: none; }
.breadcrumb-current { color: var(--color-fg); font-weight: 500; }
</style>
