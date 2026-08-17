<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MobileFormLayout from '@/components/MobileFormLayout.vue'
import FormProgressHeader from '@/components/FormProgressHeader.vue'
import SubmitSuccessPage from '@/components/SubmitSuccessPage.vue'
import { useWebFormStore } from '@/stores/webForm'
import { useWebFormWorkflowStore } from '@/stores/webFormWorkflow'

const route = useRoute()
const router = useRouter()
const runtime = useWebFormStore()
const workflow = useWebFormWorkflowStore()

const submitting = ref(false)
const instanceId = ref<string | null>(null)
const completed = ref(false)
const submissionError = ref<string | null>(null)
const failedQuestionLabel = ref<string | null>(null)
const sessionId = computed(() => route.params.sessionId as string)

async function submit() {
  submitting.value = true
  submissionError.value = null
  failedQuestionLabel.value = null
  try {
    const validation = await runtime.validateForm(sessionId.value)
    if (!validation.valid) {
      failedQuestionLabel.value = validation.failedQuestion?.label || validation.failedQuestion?.name || null
      submissionError.value = validation.message || 'Please correct the highlighted form field before submitting.'
      return
    }

    const result = await runtime.finalizeSession(sessionId.value)
    if (!result.success) {
      submissionError.value = result.validation?.message || result.message || 'The form could not be submitted.'
      return
    }
    instanceId.value = result.instanceId
    completed.value = true
  } catch {
    submissionError.value = runtime.error || 'The form could not be submitted. Please try again.'
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  try {
    const review = await workflow.fetchReview(sessionId.value)
    if (review.session.status === 'submitted') {
      instanceId.value = review.session.submissionInstanceId
      completed.value = true
    }
  } catch {
    // Workflow store exposes the error.
  }
})
</script>

<template>
  <MobileFormLayout
    :title="workflow.review?.session.formTitle || 'Submit form'"
    :show-navigation="false"
  >
    <SubmitSuccessPage v-if="completed" :instance-id="instanceId" />

    <template v-else>
      <FormProgressHeader
        :title="workflow.review?.session.formTitle || 'Form'"
        stage="submit"
        :percent="95"
      />

      <section class="submit-card">
        <h2>Ready to submit?</h2>
        <p>
          This will create the final submission from the same JavaRosa-backed session
          you reviewed. After submission, this draft can no longer be edited.
        </p>

        <dl v-if="workflow.review">
          <div>
            <dt>Project</dt>
            <dd>{{ workflow.review.session.projectName }}</dd>
          </div>
          <div>
            <dt>Answers</dt>
            <dd>{{ workflow.review.answers.length }}</dd>
          </div>
          <div>
            <dt>Attachments</dt>
            <dd>{{ workflow.review.attachments.length }}</dd>
          </div>
        </dl>

        <div v-if="submissionError || workflow.error || runtime.error" class="error" role="alert">
          <strong>Submission needs attention</strong>
          <p>{{ submissionError || workflow.error || runtime.error }}</p>
          <button
            v-if="failedQuestionLabel"
            type="button"
            class="fix-field"
            @click="router.push({ name: 'app-session-fill', params: { sessionId } })"
          >
            Fix {{ failedQuestionLabel }}
          </button>
        </div>

        <footer class="actions">
          <button
            type="button"
            class="secondary"
            @click="router.push({ name: 'app-session-review', params: { sessionId } })"
          >
            Back to review
          </button>
          <button type="button" :disabled="submitting" @click="submit">
            {{ submitting ? 'Submitting…' : 'Submit form' }}
          </button>
        </footer>
      </section>
    </template>
  </MobileFormLayout>
</template>

<style scoped>
.submit-card {
  margin-top: 1rem; border: 1px solid #e5e7eb; border-radius: .875rem;
  background: white; padding: 1.25rem;
}
h2 { margin-top: 0; }
p { color: #4b5563; line-height: 1.5; }
dl { display: grid; gap: .5rem; margin: 1.25rem 0; }
dl div { display: flex; justify-content: space-between; gap: 1rem; }
dt { color: #6b7280; }
dd { margin: 0; font-weight: 600; }
.actions { display: flex; justify-content: space-between; gap: .75rem; margin-top: 1.25rem; }
button {
  border: 0; border-radius: .5rem; background: #2563eb; color: white;
  padding: .8rem 1rem; font: inherit; font-weight: 600; cursor: pointer;
}
button.secondary { background: #e5e7eb; color: #374151; }
button:disabled { opacity: .6; cursor: not-allowed; }
.error { color: #b91c1c; margin-top: 1rem; }
.error p { color: inherit; margin: .35rem 0 .65rem; }
.fix-field { background: #b91c1c; padding: .55rem .75rem; font-size: .875rem; }
@media (max-width: 600px) { .actions { flex-direction: column-reverse; } }
</style>
