<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import MobileFormLayout from '@/components/MobileFormLayout.vue'
import { useWebFormWorkflowStore } from '@/stores/webFormWorkflow'

const router = useRouter()
const workflow = useWebFormWorkflowStore()

onMounted(() => {
  workflow.fetchForms().catch(() => undefined)
})
</script>

<template>
  <MobileFormLayout
    title="Available forms"
    subtitle="Forms you are allowed to complete in the browser."
  >
    <div v-if="workflow.error" class="notice error" role="alert">
      {{ workflow.error }}
    </div>

    <div v-if="workflow.loading && workflow.forms.length === 0" class="notice">
      Loading forms…
    </div>

    <div v-else-if="workflow.forms.length === 0" class="empty">
      <h2>No forms available</h2>
      <p>You do not currently have a published web form assigned to you.</p>
    </div>

    <section v-else class="cards" aria-label="Available forms">
      <article v-for="form in workflow.forms" :key="form.formId" class="card">
        <div>
          <p class="project">{{ form.projectName }}</p>
          <h2>{{ form.title }}</h2>
          <p v-if="form.description">{{ form.description }}</p>
          <span class="version">Version {{ form.version || '—' }}</span>
        </div>
        <button
          type="button"
          @click="router.push({ name: 'app-form-start', params: { formId: form.formId } })"
        >
          Start form
        </button>
      </article>
    </section>
  </MobileFormLayout>
</template>

<style scoped>
.cards { display: grid; gap: .875rem; }
.card, .empty, .notice {
  border: 1px solid #e5e7eb;
  border-radius: .875rem;
  background: white;
  padding: 1rem;
}
.card {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
}
.project, .version { color: #6b7280; font-size: .8125rem; }
.project { margin: 0 0 .25rem; font-weight: 700; text-transform: uppercase; }
h2 { margin: 0 0 .4rem; font-size: 1.125rem; }
.card p { margin-top: 0; }
button {
  flex: 0 0 auto;
  border: 0;
  border-radius: .5rem;
  background: #2563eb;
  color: white;
  padding: .7rem 1rem;
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}
.error { color: #b91c1c; border-color: #fecaca; }
@media (max-width: 600px) {
  .card { align-items: stretch; flex-direction: column; }
  button { width: 100%; }
}
</style>
