<script setup lang="ts">
defineProps<{
  lastActivityAt?: string | null
}>()

function formatDate(value?: string | null) {
  if (!value) return 'Not yet saved'
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}
</script>

<template>
  <span class="draft-status">
    <span class="dot" aria-hidden="true"></span>
    Draft · {{ formatDate(lastActivityAt) }}
  </span>
</template>

<style scoped>
.draft-status {
  display: inline-flex;
  align-items: center;
  gap: .4rem;
  color: #92400e;
  font-size: .8125rem;
  font-weight: 600;
}

.dot {
  width: .5rem;
  height: .5rem;
  border-radius: 50%;
  background: #f59e0b;
}
</style>
