<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MobileFormLayout from '@/components/MobileFormLayout.vue'
import { useWebFormStore } from '@/stores/webForm'
import { useWebFormWorkflowStore } from '@/stores/webFormWorkflow'

const route = useRoute()
const router = useRouter()
const webForm = useWebFormStore()
const workflow = useWebFormWorkflowStore()
const error = ref<string | null>(null)

onMounted(async () => {
  try {
    const form = await workflow.fetchForm(route.params.formId as string)
    const session = await webForm.startSession(form.formVersionId)
    await router.replace({
      name: 'app-session-fill',
      params: { sessionId: session.sessionId },
    })
  } catch (err: any) {
    error.value = err?.response?.data?.error
      || err?.response?.data?.message
      || 'Unable to start this form.'
  }
})
</script>

<template>
  <MobileFormLayout title="Starting form" :show-navigation="false">
    <div class="start-card">
      <div v-if="!error" class="spinner" aria-hidden="true"></div>
      <p v-if="!error">Preparing the latest published form…</p>
      <template v-else>
        <p class="error" role="alert">{{ error }}</p>
        <button type="button" @click="router.push({ name: 'app-forms' })">
          Back to forms
        </button>
      </template>
    </div>
  </MobileFormLayout>
</template>

<style scoped>
.start-card {
  display: grid;
  min-height: 15rem;
  place-items: center;
  align-content: center;
  gap: 1rem;
  border-radius: .875rem;
  background: white;
  padding: 2rem;
  text-align: center;
}
.spinner {
  width: 2.5rem;
  height: 2.5rem;
  border: 3px solid #dbeafe;
  border-top-color: #2563eb;
  border-radius: 50%;
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.error { color: #b91c1c; }
button {
  border: 0; border-radius: .5rem; padding: .7rem 1rem;
  background: #2563eb; color: white; font: inherit; cursor: pointer;
}
</style>
