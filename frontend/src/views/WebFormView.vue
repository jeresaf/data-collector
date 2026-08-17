<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  useWebFormStore,
  type QuestionResponse,
  type ValidationResponse,
  EVENT_QUESTION,
  EVENT_GROUP,
  EVENT_REPEAT,
  EVENT_REPEAT_JUNCTURE,
  EVENT_PROMPT_NEW_REPEAT,
  EVENT_END_OF_FORM,
  EVENT_BEGINNING_OF_FORM,
} from '@/stores/webForm'
import WebFormQuestion from '@/components/WebFormQuestion.vue'
import WebFormGroup from '@/components/WebFormGroup.vue'
import { buildSaveAnswerRequest } from '@/utils/webFormRuntime'

interface QuestionControlHandle {
  flush: (validate?: boolean) => boolean
  focusInput?: () => void
}

const route = useRoute()
const router = useRouter()
const webFormStore = useWebFormStore()

const formVersionId = computed(() => route.params.formVersionId as string | undefined)
const sessionId = computed(() => route.params.sessionId as string | undefined)
const isAppWorkflow = computed(() => String(route.path || '').startsWith('/app/'))

const isLoading = ref(false)
const showSubmitConfirm = ref(false)
const validationErrors = ref<string[]>([])
const fieldErrors = ref<Record<string, string>>({})
const fieldListQuestions = ref<QuestionResponse[]>([])
const fieldListLoading = ref(false)
const fieldListLoadError = ref<string | null>(null)
const statusMessage = ref<string | null>(null)
const currentQuestionRef = ref<QuestionControlHandle | null>(null)
const fieldQuestionRefs = new Map<string, QuestionControlHandle>()
let answerSaveChain: Promise<void> = Promise.resolve()

function questionKey(question: QuestionResponse): string {
  return question.instancePath || question.path || question.name || String(question.index)
}

function hasFieldListAppearance(appearance: string | null | undefined): boolean {
  return (appearance || '').toLowerCase().split(/\s+/).includes('field-list')
}

function currentFieldListContext() {
  const screen = webFormStore.currentScreen
  if (!screen) return null

  if (screen.group && hasFieldListAppearance(screen.group.appearanceHint)) {
    return {
      path: screen.group.path,
      instancePath: screen.group.instancePath,
      label: screen.group.label,
    }
  }

  const hierarchy = screen.question?.groupHierarchy
    || screen.group?.hierarchy
    || screen.repeatJuncture?.hierarchy
    || []
  for (let index = hierarchy.length - 1; index >= 0; index -= 1) {
    const context = hierarchy[index]
    if (hasFieldListAppearance(context.appearanceHint)) {
      return context
    }
  }
  return null
}

function groupIsFieldList(): boolean {
  return currentFieldListContext() !== null
}

function currentFieldListInstancePath(): string | null {
  const context = currentFieldListContext()
  return context ? (context.instancePath || context.path || null) : null
}

function setFieldQuestionRef(question: QuestionResponse, element: unknown) {
  const key = questionKey(question)
  if (element) {
    fieldQuestionRefs.set(key, element as QuestionControlHandle)
  } else {
    fieldQuestionRefs.delete(key)
  }
}

function setFieldError(question: QuestionResponse, message: string | null) {
  const key = questionKey(question)
  if (message) {
    fieldErrors.value[key] = message
  } else {
    delete fieldErrors.value[key]
  }
}

function getApiErrorMessage(error: any): string {
  return error?.response?.data?.error
    || error?.response?.data?.message
    || error?.message
    || 'The answer could not be saved.'
}

async function awaitPendingAnswer() {
  await answerSaveChain
}

async function refreshProgress() {
  const id = webFormStore.currentSession?.id
  if (!id) return
  try {
    await webFormStore.getProgress(id)
  } catch {
    // Progress is secondary UI state; form filling remains usable if it cannot load.
  }
}

async function refreshFieldList() {
  fieldQuestionRefs.clear()
  fieldListLoadError.value = null

  if (!groupIsFieldList() || !webFormStore.currentSession) {
    fieldListQuestions.value = []
    fieldListLoading.value = false
    return
  }

  fieldListLoading.value = true
  try {
    const result = await webFormStore.getFieldList(webFormStore.currentSession.id)
    fieldListQuestions.value = result.questions || []
  } catch (error) {
    console.error('Failed to load field-list questions:', error)
    fieldListQuestions.value = []
    fieldListLoadError.value = 'This section could not be loaded. Please retry or refresh the form.'
  } finally {
    fieldListLoading.value = false
  }
}

async function refreshScreenAuxiliaryState() {
  await Promise.all([refreshProgress(), refreshFieldList()])
}

async function initializeForm() {
  isLoading.value = true
  validationErrors.value = []
  fieldErrors.value = {}
  fieldListQuestions.value = []
  fieldListLoadError.value = null
  statusMessage.value = null
  try {
    if (sessionId.value) {
      const session = await webFormStore.getSession(sessionId.value)
      await webFormStore.getFormDefinition(session.formVersionId)
      await webFormStore.getCurrentScreen(sessionId.value)
    } else if (formVersionId.value) {
      await webFormStore.getFormDefinition(formVersionId.value)
      await webFormStore.startSession(formVersionId.value)
    } else {
      throw new Error('A form version or existing session is required')
    }
    await refreshScreenAuxiliaryState()
  } catch (err) {
    console.error('Failed to initialize form:', err)
    router.push({ name: String(route.path || '').startsWith('/app/') ? 'app-forms' : 'projects' })
  } finally {
    isLoading.value = false
  }
}

async function handleAnswerForQuestion(question: QuestionResponse, value: any) {
  const session = webFormStore.currentSession
  if (!session) return

  const request = buildSaveAnswerRequest(question, value)
  setFieldError(question, null)
  validationErrors.value = []
  statusMessage.value = null

  // Serialize answer saves. Select-multiple controls can emit several quick
  // changes and text controls can emit during the same click that presses Next.
  // A single ordered queue prevents those requests from racing navigation.
  answerSaveChain = answerSaveChain
    .catch(() => undefined)
    .then(async () => {
      try {
        await webFormStore.saveAnswer(session.id, request)
        setFieldError(question, null)
        await refreshProgress()

        // Rebuild a field-list after every committed answer. This mirrors the
        // mobile clients: relevance and dynamic itemsets inside the same screen
        // can change immediately when an earlier answer changes.
        if (groupIsFieldList()) {
          await refreshFieldList()
        }
      } catch (error) {
        const message = getApiErrorMessage(error)
        setFieldError(question, message)
        throw error
      }
    })
    .catch((error) => {
      console.error('Failed to save answer:', error)
    })

  await answerSaveChain
}

async function handleAnswer(value: any) {
  const question = webFormStore.currentScreen?.question
  if (!question) return
  await handleAnswerForQuestion(question, value)
}

async function flushVisibleQuestions(validate = true): Promise<boolean> {
  let locallyValid = true

  if (groupIsFieldList()) {
    for (const question of fieldListQuestions.value) {
      const handle = fieldQuestionRefs.get(questionKey(question))
      if (handle && handle.flush(validate) === false) {
        locallyValid = false
      }
    }
  } else if (webFormStore.currentScreen?.question && currentQuestionRef.value) {
    locallyValid = currentQuestionRef.value.flush(validate) !== false
  }

  await awaitPendingAnswer()

  const visibleQuestions = groupIsFieldList()
    ? fieldListQuestions.value
    : (webFormStore.currentScreen?.question ? [webFormStore.currentScreen.question] : [])
  const hasServerError = visibleQuestions.some((question) => !!fieldErrors.value[questionKey(question)])

  if (!locallyValid || hasServerError) {
    validationErrors.value = ['Please correct the highlighted field(s) before continuing.']
    return false
  }
  return true
}

async function handleNavigateNext() {
  const session = webFormStore.currentSession
  if (!session) return

  try {
    if (!(await flushVisibleQuestions(true))) return

    const fieldListPath = currentFieldListInstancePath()
    if (fieldListPath) {
      await webFormStore.navigateFieldListNext(session.id, fieldListPath)
    } else {
      await webFormStore.navigateNext(session.id)
    }
    validationErrors.value = []
    await refreshScreenAuxiliaryState()
  } catch (err) {
    console.error('Failed to navigate next:', err)
  }
}

async function handleNavigatePrevious() {
  const session = webFormStore.currentSession
  if (!session) return

  try {
    // Back should preserve a partially-entered draft but should not force the
    // user to satisfy a required field merely to return to an earlier screen.
    await flushVisibleQuestions(false)

    const fieldListPath = currentFieldListInstancePath()
    if (fieldListPath) {
      await webFormStore.navigateFieldListPrevious(session.id, fieldListPath)
    } else {
      await webFormStore.navigatePrevious(session.id)
    }
    validationErrors.value = []
    await refreshScreenAuxiliaryState()
  } catch (err) {
    console.error('Failed to navigate previous:', err)
  }
}

async function handleDescendIntoRepeat(index: number) {
  if (!webFormStore.currentSession) return
  try {
    await flushVisibleQuestions(false)
    await webFormStore.descendIntoRepeat(webFormStore.currentSession.id, index)
    await refreshScreenAuxiliaryState()
  } catch (err) {
    console.error('Failed to descend into repeat:', err)
  }
}

async function handleNewRepeat() {
  if (!webFormStore.currentSession) return
  try {
    await flushVisibleQuestions(false)
    await webFormStore.createNewRepeat(webFormStore.currentSession.id)
    await refreshScreenAuxiliaryState()
  } catch (err) {
    console.error('Failed to create new repeat:', err)
  }
}

async function handleDeleteRepeat() {
  if (!webFormStore.currentSession) return
  try {
    await flushVisibleQuestions(false)
    await webFormStore.deleteRepeat(webFormStore.currentSession.id)
    await refreshScreenAuxiliaryState()
  } catch (err) {
    console.error('Failed to delete repeat:', err)
  }
}

async function handleSaveDraft() {
  if (!webFormStore.currentSession) return
  try {
    await flushVisibleQuestions(false)
    await webFormStore.saveDraft(webFormStore.currentSession.id)
    statusMessage.value = 'Draft saved.'
  } catch (err) {
    console.error('Failed to save draft:', err)
    validationErrors.value = ['The draft could not be saved. Please try again.']
  }
}

async function applyValidationResult(result: ValidationResponse): Promise<boolean> {
  if (result.valid) {
    validationErrors.value = []
    return true
  }

  const message = result.message || 'Please correct the highlighted field before continuing.'
  validationErrors.value = [message]

  if (result.failedQuestion) {
    const question = result.failedQuestion
    fieldErrors.value = { [questionKey(question)]: message }
    fieldListQuestions.value = []
    webFormStore.currentScreen = {
      event: EVENT_QUESTION,
      isAtBeginning: false,
      isAtEnd: false,
      question,
      group: null,
      repeatJuncture: null,
    }

    // A failed question may belong to an appearance=field-list group. In that
    // case rebuild the whole page and focus the exact field within it instead
    // of degrading the group to a single-question screen.
    await refreshFieldList()
    await nextTick()
    const fieldListHandle = fieldQuestionRefs.get(questionKey(question))
    ;(fieldListHandle || currentQuestionRef.value)?.focusInput?.()
  }

  return false
}

async function validateCurrentForm(showSuccess = true): Promise<boolean> {
  const session = webFormStore.currentSession
  if (!session) return false

  await flushVisibleQuestions(false)
  const result = await webFormStore.validateForm(session.id)
  const valid = await applyValidationResult(result)
  if (valid && showSuccess) statusMessage.value = 'All currently required answers are valid.'
  return valid
}

async function handleValidate() {
  try {
    await validateCurrentForm(true)
  } catch (err) {
    console.error('Failed to validate form:', err)
    validationErrors.value = ['The form could not be validated. Please try again.']
  }
}

async function handleSubmit() {
  if (!webFormStore.currentSession) return

  try {
    if (!(await validateCurrentForm(false))) return

    if (isAppWorkflow.value) {
      await router.push({
        name: 'app-session-review',
        params: { sessionId: webFormStore.currentSession.id },
      })
      return
    }

    showSubmitConfirm.value = true
  } catch (err) {
    console.error('Failed to prepare form for submission:', err)
    validationErrors.value = ['The form could not be prepared for submission. Please try again.']
  }
}

async function confirmSubmit() {
  const session = webFormStore.currentSession
  if (!session) return

  try {
    if (!(await validateCurrentForm(false))) {
      showSubmitConfirm.value = false
      return
    }

    const result = await webFormStore.finalizeSession(session.id)
    if (result.success) {
      statusMessage.value = 'Form submitted successfully.'
      router.push({ name: 'submissions' })
      return
    }

    if (result.validation) {
      await applyValidationResult(result.validation)
    } else {
      validationErrors.value = [result.message || 'The form could not be submitted.']
    }
  } catch (err) {
    console.error('Failed to submit form:', err)
    validationErrors.value = [getApiErrorMessage(err)]
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

const currentEvent = computed(() => webFormStore.currentScreen?.event ?? 0)

const currentGroupHierarchy = computed(() => {
  const screen = webFormStore.currentScreen
  if (!screen) return []
  if (screen.question?.groupHierarchy) return screen.question.groupHierarchy
  if (screen.group?.hierarchy) return screen.group.hierarchy
  if (screen.repeatJuncture?.hierarchy) return screen.repeatJuncture.hierarchy
  return []
})

const visibleGroupHierarchy = computed(() => {
  const hierarchy = currentGroupHierarchy.value
  const fieldList = currentFieldListContext()
  if (!fieldList || hierarchy.length === 0) return hierarchy

  // The field-list section itself is rendered as a heading below. Keep only
  // its parents here so the same title is not shown twice.
  const fieldListKey = fieldList.instancePath || fieldList.path
  return hierarchy.filter((context) =>
    (context.instancePath || context.path) !== fieldListKey,
  )
})

const currentRepeatContexts = computed(() =>
  currentGroupHierarchy.value.filter(
    (context) => context.repeat && context.repeatIndex !== null && context.repeatIndex !== undefined,
  ),
)

const progressPercent = computed(() => webFormStore.progress?.percent ?? 0)
const answeredQuestions = computed(() => webFormStore.progress?.answeredQuestions ?? 0)
const totalQuestions = computed(() => webFormStore.progress?.totalQuestions ?? 0)

watch(
  () => [route.params.formVersionId, route.params.sessionId],
  () => { initializeForm() },
  { immediate: true },
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
          <span class="progress-text">{{ answeredQuestions }}/{{ totalQuestions }} · {{ Math.round(progressPercent) }}%</span>
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

      <div v-if="statusMessage" class="status-banner" role="status">
        <span>{{ statusMessage }}</span>
        <button @click="statusMessage = null" class="btn-close btn-close-neutral" aria-label="Dismiss message">×</button>
      </div>

      <!-- Form Body -->
      <main class="form-body">
        <!-- Nested group/repeat context -->
        <nav
          v-if="visibleGroupHierarchy.length > 0"
          class="group-breadcrumbs"
          aria-label="Form section"
        >
          <template v-for="(context, index) in visibleGroupHierarchy" :key="context.instancePath || `${context.path}-${index}`">
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
        </div>

        <!-- appearance=field-list: render the whole enclosing group as one page,
             even when a resumed JavaRosa session is currently positioned on a
             child question rather than on the GROUP event itself. -->
        <WebFormGroup
          v-if="groupIsFieldList()"
          :group="webFormStore.currentScreen?.group || {
            label: currentFieldListContext()?.label || 'Section',
            path: currentFieldListContext()?.path || null,
            instancePath: currentFieldListContext()?.instancePath || null,
            depth: currentGroupHierarchy.length ? currentGroupHierarchy.length - 1 : 0,
            hierarchy: currentGroupHierarchy,
            isRepeat: false,
            repeatCount: 0,
            currentRepeatIndex: null,
            repeatTargetCount: null,
            repeatCountControlled: false,
            canAddRepeat: false,
            canDeleteRepeat: false,
            appearanceHint: 'field-list',
          }"
          :event="webFormStore.currentScreen?.group ? currentEvent : EVENT_GROUP"
          :field-list="true"
        >
          <div class="field-list-questions">
            <div v-if="fieldListLoading" class="field-list-loading" role="status">
              <span class="inline-spinner" aria-hidden="true"></span>
              Loading section…
            </div>
            <WebFormQuestion
              v-for="question in fieldListQuestions"
              :key="questionKey(question)"
              :ref="(element) => setFieldQuestionRef(question, element)"
              :question="question"
              :value="question.currentAnswer"
              :external-error="fieldErrors[questionKey(question)] || null"
              @answer="(value) => handleAnswerForQuestion(question, value)"
            />
            <p v-if="fieldListLoadError" class="field-list-error" role="alert">
              {{ fieldListLoadError }}
            </p>
            <p
              v-else-if="!fieldListLoading && fieldListQuestions.length === 0"
              class="field-list-empty"
            >
              There are no questions to answer in this section right now.
            </p>
          </div>
        </WebFormGroup>

        <!-- Question -->
        <WebFormQuestion
          v-else-if="currentEvent === EVENT_QUESTION && webFormStore.currentScreen?.question"
          ref="currentQuestionRef"
          :question="webFormStore.currentScreen.question"
          :value="webFormStore.currentScreen.question.currentAnswer"
          :external-error="fieldErrors[questionKey(webFormStore.currentScreen.question)] || null"
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
        />

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
            <button @click="handleSubmit" class="btn-primary">
              {{ isAppWorkflow ? 'Review answers' : 'Submit Form' }}
            </button>
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


    <!-- Legacy technical-route submit confirmation. The /app workflow uses
         dedicated review and submit pages instead. -->
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
.compatibility-banner,
.status-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.75rem 1rem;
  background: #fef2f2;
  border-bottom: 1px solid #fecaca;
  color: #dc2626;
  font-size: 0.875rem;
}

.compatibility-banner {
  background: #fff7ed;
  border-bottom-color: #fed7aa;
  color: #9a3412;
}

.status-banner {
  background: #f0fdf4;
  border-bottom-color: #bbf7d0;
  color: #166534;
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

.btn-close-neutral {
  color: #166534;
}

.btn-close-neutral:hover {
  color: #14532d;
}

.validation-banner ul,
.compatibility-banner ul {
  margin: 0;
  padding-left: 1.25rem;
}

.form-body {
  flex: 1;
  padding: 1.25rem 1.5rem 2rem;
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
  justify-content: flex-start;
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

.field-list-questions {
  display: flex;
  flex-direction: column;
}

.field-list-loading,
.field-list-empty,
.field-list-error {
  display: flex;
  align-items: center;
  gap: 0.55rem;
  margin: 0;
  padding: 1rem 0;
  color: #64748b;
  font-size: 0.875rem;
}

.field-list-error {
  color: #b91c1c;
}

.inline-spinner {
  width: 0.95rem;
  height: 0.95rem;
  border: 2px solid #cbd5e1;
  border-top-color: #2563eb;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
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
  background: #2563eb;
  color: white;
}

.btn-primary:hover:not(:disabled) {
  background: #1d4ed8;
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