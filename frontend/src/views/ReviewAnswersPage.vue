<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MobileFormLayout from '@/components/MobileFormLayout.vue'
import FormProgressHeader from '@/components/FormProgressHeader.vue'
import { useWebFormStore } from '@/stores/webForm'
import { useWebFormWorkflowStore } from '@/stores/webFormWorkflow'

const route = useRoute()
const router = useRouter()
const runtime = useWebFormStore()
const workflow = useWebFormWorkflowStore()
const validationMessage = ref<string | null>(null)
const validating = ref(false)

const sessionId = computed(() => route.params.sessionId as string)
const review = computed(() => workflow.review)

function answerLabel(answer: any) {
  return answer.questionName || answer.questionPath || 'Answer'
}

function displayValue(value: unknown) {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

async function validateAndContinue() {
  validating.value = true
  validationMessage.value = null
  try {
    const result = await runtime.validateForm(sessionId.value)
    if (!result.valid) {
      validationMessage.value = result.failedPromptIndex
        ? `Validation failed near ${result.failedPromptIndex}. Continue editing to correct the form.`
        : 'The form still contains validation errors.'
      return
    }
    await router.push({
      name: 'app-session-submit',
      params: { sessionId: sessionId.value },
    })
  } catch {
    validationMessage.value = runtime.error || 'Unable to validate this form.'
  } finally {
    validating.value = false
  }
}

onMounted(async () => {
  try {
    const result = await workflow.fetchReview(sessionId.value)
    if (result.session.status === 'submitted') {
      await router.replace({ name: 'app-submitted' })
    }
  } catch {
    // Store exposes the user-facing error.
  }
})
</script>

<template>
  <MobileFormLayout
    :title="review?.session.formTitle || 'Review answers'"
    subtitle="Check your answers before submitting."
    :show-navigation="false"
  >
    <FormProgressHeader
      :title="review?.session.formTitle || 'Form'"
      stage="review"
      :percent="85"
    />

    <p v-if="workflow.error" class="notice error" role="alert">{{ workflow.error }}</p>
    <p v-else-if="workflow.loading && !review" class="notice">Loading answers…</p>

    <template v-else-if="review">
      <section class="review-card">
        <div
          v-for="answer in review.answers"
          :key="answer.instancePath || answer.id"
          class="answer-row"
        >
          <div>
            <strong>{{ answerLabel(answer) }}</strong>
            <small v-if="answer.repeatPath">
              {{ answer.repeatPath }}
              <template v-if="answer.repeatIndex !== null">
                · entry {{ answer.repeatIndex + 1 }}
              </template>
            </small>
          </div>
          <span>{{ displayValue(answer.value) }}</span>
        </div>

        <div v-if="review.answers.length === 0" class="empty">
          No persisted answers are available for review.
        </div>
      </section>

      <section v-if="review.attachments.length" class="attachment-card">
        <h2>Attachments</h2>
        <div v-for="attachment in review.attachments" :key="attachment.id" class="attachment">
          <strong>{{ attachment.fileName }}</strong>
          <span>{{ attachment.contentType || 'File' }}</span>
        </div>
      </section>

      <p v-if="validationMessage" class="notice error" role="alert">
        {{ validationMessage }}
      </p>

      <footer class="actions">
        <button
          type="button"
          class="secondary"
          @click="router.push({ name: 'app-session-fill', params: { sessionId } })"
        >
          Continue editing
        </button>
        <button type="button" :disabled="validating" @click="validateAndContinue">
          {{ validating ? 'Validating…' : 'Continue to submit' }}
        </button>
      </footer>
    </template>
  </MobileFormLayout>
</template>

<style scoped>
.review-card, .attachment-card, .notice {
  margin-top: 1rem; border: 1px solid #e5e7eb; border-radius: .875rem;
  background: white; padding: 1rem;
}
.answer-row, .attachment {
  display: flex; justify-content: space-between; align-items: flex-start;
  gap: 1rem; padding: .875rem 0; border-bottom: 1px solid #f3f4f6;
}
.answer-row:last-child, .attachment:last-child { border-bottom: 0; }
.answer-row > span { max-width: 55%; text-align: right; overflow-wrap: anywhere; }
small { display: block; margin-top: .2rem; color: #6b7280; }
.attachment-card h2 { margin-top: 0; font-size: 1rem; }
.attachment span { color: #6b7280; font-size: .875rem; }
.actions { display: flex; justify-content: space-between; gap: .75rem; margin-top: 1rem; }
button {
  border: 0; border-radius: .5rem; background: #2563eb; color: white;
  padding: .8rem 1rem; font: inherit; font-weight: 600; cursor: pointer;
}
button.secondary { background: #e5e7eb; color: #374151; }
button:disabled { opacity: .6; cursor: not-allowed; }
.error { color: #b91c1c; border-color: #fecaca; }
.empty { color: #6b7280; }
@media (max-width: 600px) {
  .answer-row { flex-direction: column; }
  .answer-row > span { max-width: none; text-align: left; }
  .actions { flex-direction: column-reverse; }
}
</style>
