<script setup lang="ts">
const props = withDefaults(defineProps<{
  title: string
  stage: 'fill' | 'review' | 'submit' | 'complete'
  percent?: number
}>(), {
  percent: 0,
})

const labels = {
  fill: 'Fill form',
  review: 'Review answers',
  submit: 'Submit',
  complete: 'Complete',
}
</script>

<template>
  <section class="progress-header" aria-label="Form progress">
    <div class="progress-copy">
      <strong>{{ title }}</strong>
      <span>{{ labels[props.stage] }}</span>
    </div>
    <div
      class="progress-track"
      role="progressbar"
      :aria-valuenow="Math.max(0, Math.min(100, percent))"
      aria-valuemin="0"
      aria-valuemax="100"
    >
      <span :style="{ width: `${Math.max(0, Math.min(100, percent))}%` }"></span>
    </div>
  </section>
</template>

<style scoped>
.progress-header {
  padding: 1rem;
  border: 1px solid #e5e7eb;
  border-radius: .75rem;
  background: white;
}

.progress-copy {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: .625rem;
}

.progress-copy span {
  color: #6b7280;
  font-size: .875rem;
}

.progress-track {
  height: .45rem;
  overflow: hidden;
  border-radius: 999px;
  background: #e5e7eb;
}

.progress-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: #2563eb;
}
</style>
