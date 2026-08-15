<script setup lang="ts">
import { onMounted, ref, watch, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useWebFormStore, EVENT_QUESTION, EVENT_GROUP, EVENT_REPEAT, EVENT_REPEAT_JUNCTURE, EVENT_PROMPT_NEW_REPEAT, EVENT_END_OF_FORM, EVENT_BEGINNING_OF_FORM } from '@/stores/webForm'
import WebFormQuestion from '@/components/WebFormQuestion.vue'
import WebFormGroup from '@/components/WebFormGroup.vue'
import { buildSaveAnswerRequest } from '@/utils/webFormRuntime'

const route = useRoute()
const router = useRouter()
const webFormStore = useWebFormStore()

const formVersionId = computed(() => route.params.formVersionId as string)
const sessionId = computed(() => route.params.sessionId as string | undefined)

const isLoading = ref(false)
const showSubmitConfirm = ref(false)
const validationErrors = ref<string[]>([])

async function initializeForm() {
  isLoading.value = true
  try {
    await webFormStore.getFormDefinition(formVersionId.value)

    if (sessionId.value) {
      // Resume existing session.
      await webFormStore.getSession(sessionId.value)
      await webFormStore.getCurrentScreen(sessionId.value)
    } else {
      // Start a new JavaRosa-backed session.
      await webFormStore.startSession(formVersionId.value)
    }
  } catch (err) {
    console.error('Failed to initialize form:', err)
    router.push({ name: 'projects' })
  } finally {
    isLoading.value = false
  }
}

async function handleAnswer(value: any) {
  if (!webFormStore.currentSession) return
  
  const question = webFormStore.currentScreen?.question
  if (!question) return

  try {
    await webFormStore.saveAnswer(
      webFormStore.currentSession.id,
      buildSaveAnswerRequest(question, value),
    )
  } catch (err) {
    console.error('Failed to save answer:', err)
  }
}

async function handleNavigateNext() {
  if (!webFormStore.currentSession) return
  
  try {
    await webFormStore.navigateNext(webFormStore.currentSession.id)
  } catch (err) {
    console.error('Failed to navigate next:', err)
  }
}

async function handleNavigatePrevious() {
  if (!webFormStore.currentSession) return
  
  try {
    await webFormStore.navigatePrevious(webFormStore.currentSession.id)
  } catch (err) {
    console.error('Failed to navigate previous:', err)
  }
}

async function handleDescendIntoRepeat(index: number) {
  if (!webFormStore.currentSession) return
  
  try {
    await webFormStore.descendIntoRepeat(webFormStore.currentSession.id, index)
  } catch (err) {
    console.error('Failed to descend into repeat:', err)
  }
}

async function handleNewRepeat() {
  if (!webFormStore.currentSession) return
  
  try {
    await webFormStore.createNewRepeat(webFormStore.currentSession.id)
  } catch (err) {
    console.error('Failed to create new repeat:', err)
  }
}

async function handleDeleteRepeat() {
  if (!webFormStore.currentSession) return
  
  try {
    await webFormStore.deleteRepeat(webFormStore.currentSession.id)
  } catch (err) {
    console.error('Failed to delete repeat:', err)
  }
}

async function handleSaveDraft() {
  if (!webFormStore.currentSession) return
  
  try {
    await webFormStore.saveDraft(webFormStore.currentSession.id)
    alert('Draft saved successfully')
  } catch (err) {
    console.error('Failed to save draft:', err)
    alert('Failed to save draft')
  }
}

async function handleValidate() {
  if (!webFormStore.currentSession) return
  
  try {
    const result = await webFormStore.validateForm(webFormStore.currentSession.id)
    if (result.valid) {
      alert('Form is valid!')
    } else {
      validationErrors.value = [`Validation failed at: ${result.failedPromptIndex}`]
    }
  } catch (err) {
    console.error('Failed to validate form:', err)
  }
}

async function handleSubmit() {
  if (!webFormStore.currentSession) return
  
  showSubmitConfirm.value = true
}

async function confirmSubmit() {
  if (!webFormStore.currentSession) return
  
  try {
    const result = await webFormStore.finalizeSession(webFormStore.currentSession.id)
    if (result.success) {
      alert('Form submitted successfully!')
      router.push({ name: 'submissions' })
    } else {
      alert(`Submission failed: ${result.message}`)
    }
  } catch (err) {
    console.error('Failed to submit form:', err)
    alert('Failed to submit form')
  } finally {
    showSubmitConfirm.value = false
  }
}

const canGoNext = computed(() => {
  const screen = webFormStore.currentScreen
  if (!screen) return false
  return screen.event !== EVENT_END_OF_FORM && screen.event !== EVENT_BEGINNING_OF_FORM
})

const canGoPrevious = computed(() => {
  const screen = webFormStore.currentScreen
  if (!screen) return false
  return !screen.isAtBeginning
})

const currentEvent = computed(() => {
  return webFormStore.currentScreen?.event ?? 0
})

const currentGroupHierarchy = computed(() => {
  const screen = webFormStore.currentScreen
  if (!screen) return []

  if (screen.question?.groupHierarchy) return screen.question.groupHierarchy
  if (screen.group?.hierarchy) return screen.group.hierarchy
  if (screen.repeatJuncture?.hierarchy) return screen.repeatJuncture.hierarchy
  return []
})

const currentRepeatContexts = computed(() =>
  currentGroupHierarchy.value.filter(
    (context) => context.repeat && context.repeatIndex !== null && context.repeatIndex !== undefined,
  ),
)

const progressPercent = computed(() => {
  // Simple progress based on question index
  const question = webFormStore.currentScreen?.question
  if (question) {
    return Math.min((question.index / 20) * 100, 100) // Rough estimate
  }
  return 0
})

onMounted(() => {
  initializeForm()
})

watch(
  () => [route.params.formVersionId, route.params.sessionId],
  () => {
    initializeForm()
  },
)
</script>

<template>
  <div class="web-form-view">
    <!-- Loading State -->
    <div v-if="isLoading" class="loading-overlay">
      <div class="spinner"></div>
      <p>Loading form...</p>
    </div>

    <!-- Form Content -->
    <div v-else class="form-container">
      <!-- Header -->
      <header class="form-header">
        <div class="header-left">
          <h1 v-if="webFormStore.formDefinition">{{ webFormStore.formDefinition.title }}</h1>
          <h1 v-else>{{ webFormStore.currentScreen?.question?.label || 'Form' }}</h1>
        </div>
        <div class="header-right">
          <div class="progress-bar" role="progressbar" :aria-valuenow="progressPercent" aria-valuemin="0" aria-valuemax="100">
            <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
          </div>
          <span class="progress-text">{{ Math.round(progressPercent) }}%</span>
        </div>
      </header>

      <!-- Error Banner -->
      <div v-if="webFormStore.error" class="error-banner">
        <span>{{ webFormStore.error }}</span>
        <button @click="webFormStore.clearError" class="btn-close" aria-label="Dismiss error">×</button>
      </div>

      <!-- XForm compatibility warnings: never silently ignore logic the web runtime cannot guarantee. -->
      <div
        v-if="webFormStore.formDefinition?.compatibilityWarnings?.length"
        class="compatibility-banner"
        role="status"
      >
        <strong>Form compatibility notice</strong>
        <ul>
          <li
            v-for="warning in webFormStore.formDefinition.compatibilityWarnings"
            :key="warning"
          >
            {{ warning }}
          </li>
        </ul>
      </div>

      <!-- Validation Errors -->
      <div v-if="validationErrors.length > 0" class="validation-banner">
        <ul>
          <li v-for="error in validationErrors" :key="error">{{ error }}</li>
        </ul>
        <button @click="validationErrors = []" class="btn-close" aria-label="Dismiss errors">×</button>
      </div>

      <!-- Form Body -->
      <main class="form-body">
        <!-- Nested group/repeat context -->
        <nav
          v-if="currentGroupHierarchy.length > 0"
          class="group-breadcrumbs"
          aria-label="Form section"
        >
          <template v-for="(context, index) in currentGroupHierarchy" :key="context.instancePath || `${context.path}-${index}`">
            <span v-if="index > 0" class="group-breadcrumb-separator" aria-hidden="true">›</span>
            <span class="group-breadcrumb" :class="{ 'group-breadcrumb-repeat': context.repeat }">
              {{ context.label || context.path || 'Section' }}
              <span v-if="context.repeat && context.repeatIndex !== null" class="group-breadcrumb-index">
                · entry {{ context.repeatIndex + 1 }}
              </span>
            </span>
          </template>
        </nav>

        <div
          v-for="context in currentRepeatContexts"
          :key="context.instancePath || context.path || context.depth"
          class="repeat-context"
          role="status"
        >
          <span class="repeat-context-label">{{ context.label || 'Repeat' }} · entry {{ (context.repeatIndex ?? 0) + 1 }}</span>
          <span class="repeat-context-path">{{ context.path }}</span>
        </div>

        <!-- Question -->
        <WebFormQuestion
          v-if="currentEvent === EVENT_QUESTION && webFormStore.currentScreen?.question"
          :question="webFormStore.currentScreen.question"
          :value="webFormStore.currentScreen.question.currentAnswer"
          @answer="handleAnswer"
        />

        <!-- Group -->
        <WebFormGroup
          v-else-if="(currentEvent === EVENT_GROUP || currentEvent === EVENT_REPEAT) && webFormStore.currentScreen?.group"
          :group="webFormStore.currentScreen.group"
          :event="currentEvent"
          @descend="handleDescendIntoRepeat"
          @new-repeat="handleNewRepeat"
          @delete-repeat="handleDeleteRepeat"
        >
          <!-- Group content - questions inside group -->
          <WebFormQuestion
            v-if="webFormStore.currentScreen?.question"
            :question="webFormStore.currentScreen.question"
            :value="webFormStore.currentScreen.question.currentAnswer"
            @answer="handleAnswer"
          />
        </WebFormGroup>

        <!-- Repeat Juncture -->
        <WebFormGroup
          v-else-if="(currentEvent === EVENT_REPEAT_JUNCTURE || currentEvent === EVENT_PROMPT_NEW_REPEAT) && webFormStore.currentScreen?.repeatJuncture"
          :group="webFormStore.currentScreen.repeatJuncture"
          :event="currentEvent"
          @descend="handleDescendIntoRepeat"
          @new-repeat="handleNewRepeat"
          @delete-repeat="handleDeleteRepeat"
        />

        <!-- End of Form -->
        <div v-else-if="currentEvent === EVENT_END_OF_FORM" class="form-complete">
          <div class="complete-icon">✓</div>
          <h2>Form Complete</h2>
          <p>You have reached the end of the form. Review your answers and submit when ready.</p>
          <div class="complete-actions">
            <button @click="handleValidate" class="btn-secondary">Validate Form</button>
            <button @click="handleSubmit" class="btn-primary">Submit Form</button>
          </div>
        </div>

        <!-- Beginning of Form -->
        <div v-else-if="currentEvent === EVENT_BEGINNING_OF_FORM" class="form-start">
          <h2>Welcome</h2>
          <p>Start filling out the form by clicking "Next" below.</p>
        </div>

        <!-- Unknown State -->
        <div v-else class="form-unknown">
          <p>Loading form content...</p>
        </div>
      </main>

      <!-- Sticky Form Actions -->
      <footer class="form-actions" v-show="currentEvent !== EVENT_END_OF_FORM && currentEvent !== EVENT_BEGINNING_OF_FORM">
        <div class="actions-left">
          <button
            @click="handleNavigatePrevious"
            :disabled="!canGoPrevious || webFormStore.loading"
            class="btn-secondary"
          >
            ← Back
          </button>
          <button
            @click="handleSaveDraft"
            :disabled="webFormStore.loading"
            class="btn-outline"
          >
            Save Draft
          </button>
        </div>
        <div class="actions-right">
          <button
            @click="handleNavigateNext"
            :disabled="!canGoNext || webFormStore.loading"
            class="btn-primary"
          >
            Next →
          </button>
        </div>
      </footer>
    </div>

    <!-- Submit Confirmation Modal -->
    <div v-if="showSubmitConfirm" class="modal-overlay" @click.self="showSubmitConfirm = false">
      <div class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
        <h3 id="modal-title">Submit Form</h3>
        <p>Are you sure you want to submit this form? Once submitted, you cannot edit it.</p>
        <div class="modal-actions">
          <button @click="showSubmitConfirm = false" class="btn-secondary">Cancel</button>
          <button @click="confirmSubmit" :disabled="webFormStore.loading" class="btn-primary">Submit</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.web-form-view {
  min-height: 100vh;
  background: #f3f4f6;
}

.loading-overlay {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  gap: 1rem;
}

.spinner {
  width: 40px;
  height: 40px;
  border: 3px solid #e5e7eb;
  border-top-color: #3b82f6;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.form-container {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  max-width: 720px;
  margin: 0 auto;
  background: white;
  box-shadow: 0 0 20px rgba(0,0,0,0.05);
}

.form-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem;
  border-bottom: 1px solid #e5e7eb;
  background: #fff;
  position: sticky;
  top: 0;
  z-index: 10;
}

.header-left h1 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
  color: #1f2937;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.progress-bar {
  width: 150px;
  height: 6px;
  background: #e5e7eb;
  border-radius: 3px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: #3b82f6;
  transition: width 0.3s ease;
}

.progress-text {
  font-size: 0.75rem;
  color: #6b7280;
  font-weight: 500;
  min-width: 35px;
}

.error-banner,
.validation-banner,
.compatibility-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.75rem 1rem;
  background: #fef2f2;
  border-bottom: 1px solid #fecaca;
  color: #dc2626;
  font-size: 0.875rem;
}

.btn-close {
  background: none;
  border: none;
  font-size: 1.25rem;
  color: #dc2626;
  cursor: pointer;
  padding: 0 0.5rem;
  line-height: 1;
}

.btn-close:hover {
  color: #991b1b;
}

.validation-banner ul,
.compatibility-banner ul {
  margin: 0;
  padding-left: 1.25rem;
}

.form-body {
  flex: 1;
  padding: 1.5rem;
  overflow-y: auto;
}

.group-breadcrumbs {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.375rem;
  margin-bottom: 0.75rem;
  color: #6b7280;
  font-size: 0.8125rem;
}

.group-breadcrumb {
  display: inline-flex;
  align-items: center;
  min-width: 0;
}

.group-breadcrumb-repeat {
  color: #92400e;
  font-weight: 600;
}

.group-breadcrumb-index {
  font-weight: 500;
}

.group-breadcrumb-separator {
  color: #9ca3af;
}

.repeat-context {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
  padding: 0.625rem 0.75rem;
  border: 1px solid #fde68a;
  border-radius: 6px;
  background: #fffbeb;
  color: #92400e;
  font-size: 0.8125rem;
}

.repeat-context-label {
  font-weight: 600;
}

.repeat-context-path {
  overflow-wrap: anywhere;
  color: #a16207;
}

.form-complete,
.form-start,
.form-unknown {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 3rem 1rem;
  gap: 1rem;
}

.complete-icon {
  width: 64px;
  height: 64px;
  background: #dcfce7;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 2rem;
  color: #16a34a;
}

.complete-actions,
.start-actions {
  display: flex;
  gap: 0.75rem;
  flex-wrap: wrap;
  justify-content: center;
}

.form-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem;
  border-top: 1px solid #e5e7eb;
  background: #fff;
  position: sticky;
  bottom: 0;
  z-index: 10;
  gap: 1rem;
}

.actions-left,
.actions-right {
  display: flex;
  gap: 0.5rem;
}

.btn-primary,
.btn-secondary,
.btn-outline,
.btn-danger {
  padding: 0.75rem 1.5rem;
  border-radius: 6px;
  font-size: 1rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  border: none;
  white-space: nowrap;
}

.btn-primary {
  background: #3b82f6;
  color: white;
}

.btn-primary:hover:not(:disabled) {
  background: #2563eb;
}

.btn-primary:disabled {
  background: #93c5fd;
  cursor: not-allowed;
}

.btn-secondary {
  background: #e5e7eb;
  color: #374151;
}

.btn-secondary:hover:not(:disabled) {
  background: #d1d5db;
}

.btn-secondary:disabled {
  background: #f3f4f6;
  color: #9ca3af;
  cursor: not-allowed;
}

.btn-outline {
  background: transparent;
  color: #3b82f6;
  border: 1px solid #3b82f6;
}

.btn-outline:hover:not(:disabled) {
  background: #eff6ff;
}

.btn-outline:disabled {
  color: #93c5fd;
  border-color: #93c5fd;
  cursor: not-allowed;
}

.btn-danger {
  background: #ef4444;
  color: white;
}

.btn-danger:hover:not(:disabled) {
  background: #dc2626;
}

.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  z-index: 100;
}

.modal {
  background: white;
  border-radius: 12px;
  padding: 1.5rem;
  max-width: 400px;
  width: 100%;
  box-shadow: 0 20px 25px -5px rgba(0,0,0,0.1);
}

.modal h3 {
  margin: 0 0 0.5rem;
  font-size: 1.25rem;
  color: #1f2937;
}

.modal p {
  margin: 0 0 1.5rem;
  color: #6b7280;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.75rem;
}

/* Responsive */
@media (max-width: 640px) {
  .form-header {
    flex-direction: column;
    gap: 0.75rem;
    align-items: flex-start;
  }
  
  .header-right {
    width: 100%;
    justify-content: space-between;
  }
  
  .progress-bar {
    flex: 1;
  }
  
  .form-actions {
    flex-direction: column-reverse;
  }
  
  .actions-left,
  .actions-right {
    width: 100%;
    justify-content: space-between;
  }
  
  .btn-primary,
  .btn-secondary,
  .btn-outline {
    flex: 1;
    text-align: center;
  }
}
</style>