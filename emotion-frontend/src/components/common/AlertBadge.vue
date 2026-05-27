<template>
  <div class="alert-badge" @click="$emit('click')">
    <span class="badge-icon">🔔</span>
    <span v-if="count > 0" class="badge-count" :class="badgeClass">{{ count > 99 ? '99+' : count }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ count: number }>()
defineEmits(['click'])
const badgeClass = computed(() => {
  if (props.count >= 6) return 'badge-danger'
  if (props.count >= 1) return 'badge-warning'
  return ''
})
</script>

<style scoped>
.alert-badge { position: relative; cursor: pointer; padding: var(--space-2); }
.badge-icon { font-size: 20px; }
.badge-count {
  position: absolute; top: 0; right: 0; min-width: 18px; height: 18px;
  border-radius: 9px; font-size: 10px; font-weight: 600;
  display: flex; align-items: center; justify-content: center;
  background: var(--color-muted-fg); color: white;
}
.badge-warning { background: var(--color-accent); }
.badge-danger { background: var(--color-destructive); animation: pulse 2s infinite; }
@keyframes pulse { 0%, 100% { transform: scale(1); } 50% { transform: scale(1.15); } }
</style>
