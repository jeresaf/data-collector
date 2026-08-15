<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import MobileFormLayout from '@/components/MobileFormLayout.vue'
import DraftStatusIndicator from '@/components/DraftStatusIndicator.vue'
import { useWebFormWorkflowStore } from '@/stores/webFormWorkflow'

const router = useRouter()
const workflow = useWebFormWorkflowStore()

onMounted(() => {
  workflow.fetchSessions('draft').catch(() => undefined)
})
</script>

<template>
  <MobileFormLayout title="Drafts" subtitle="Saved forms that you can continue later.">
    <p v-if="workflow.error" class="notice error" role="alert">{{ workflow.error }}</p>
    <p v-else-if="workflow.loading && workflow.drafts.length === 0" class="notice">
      Loading drafts…
    </p>
    <section v-else-if="workflow.drafts.length" class="cards">
      <article v-for="draft in workflow.drafts" :key="draft.sessionId" class="card">
        <div>
          <p class="project">{{ draft.projectName }}</p>
          <h2>{{ draft.formTitle }}</h2>
          <DraftStatusIndicator :last-activity-at="draft.lastActivityAt" />
        </div>
        <button
          type="button"
          @click="router.push({ name: 'app-session-fill', params: { sessionId: draft.sessionId } })"
        >
          Continue
        </button>
      </article>
    </section>
    <div v-else class="notice">You have no saved drafts.</div>
  </MobileFormLayout>
</template>

<style scoped>
.cards { display: grid; gap: .875rem; }
.card, .notice {
  border: 1px solid #e5e7eb; border-radius: .875rem;
  background: white; padding: 1rem;
}
.card { display: flex; justify-content: space-between; align-items: center; gap: 1rem; }
.project { margin: 0 0 .25rem; color: #6b7280; font-size: .75rem; font-weight: 700; text-transform: uppercase; }
h2 { margin: 0 0 .5rem; font-size: 1.125rem; }
button {
  border: 0; border-radius: .5rem; background: #2563eb; color: white;
  padding: .7rem 1rem; font: inherit; font-weight: 600; cursor: pointer;
}
.error { color: #b91c1c; }
@media (max-width: 600px) {
  .card { align-items: stretch; flex-direction: column; }
}
</style>
