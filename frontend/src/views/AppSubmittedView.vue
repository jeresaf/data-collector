<script setup lang="ts">
import { onMounted } from 'vue'
import MobileFormLayout from '@/components/MobileFormLayout.vue'
import { useWebFormWorkflowStore } from '@/stores/webFormWorkflow'

const workflow = useWebFormWorkflowStore()

function formatDate(value: string | null) {
  if (!value) return 'Submitted'
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

onMounted(() => {
  workflow.fetchSessions('submitted').catch(() => undefined)
})
</script>

<template>
  <MobileFormLayout
    title="Submitted"
    subtitle="Forms you have successfully sent."
  >
    <p v-if="workflow.error" class="notice error" role="alert">{{ workflow.error }}</p>
    <p v-else-if="workflow.loading && workflow.submitted.length === 0" class="notice">
      Loading submissions…
    </p>

    <section v-else-if="workflow.submitted.length" class="cards">
      <article v-for="item in workflow.submitted" :key="item.sessionId" class="card">
        <p class="project">{{ item.projectName }}</p>
        <h2>{{ item.formTitle }}</h2>
        <p class="success">✓ {{ formatDate(item.submittedAt || item.completedAt) }}</p>
        <p v-if="item.submissionInstanceId" class="receipt">
          Instance: {{ item.submissionInstanceId }}
        </p>
      </article>
    </section>

    <div v-else class="notice">You have no submitted web forms yet.</div>
  </MobileFormLayout>
</template>

<style scoped>
.cards { display: grid; gap: .875rem; }
.card, .notice {
  border: 1px solid #e5e7eb; border-radius: .875rem;
  background: white; padding: 1rem;
}
.project { margin: 0 0 .25rem; color: #6b7280; font-size: .75rem; font-weight: 700; text-transform: uppercase; }
h2 { margin: 0 0 .5rem; font-size: 1.125rem; }
.success { margin: .25rem 0; color: #15803d; font-weight: 600; }
.receipt { color: #6b7280; font-family: monospace; overflow-wrap: anywhere; }
.error { color: #b91c1c; }
</style>
