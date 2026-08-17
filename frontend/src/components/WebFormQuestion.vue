<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import WebFormGeoControl from './WebFormGeoControl.vue'
import WebFormSignature from './WebFormSignature.vue'
import WebFormBarcode from './WebFormBarcode.vue'
import api from '@/services/api'
import {
  useWebFormStore,
  type QuestionResponse,
  CONTROL_SELECT_ONE,
  CONTROL_SELECT_MULTI,
  CONTROL_UPLOAD,
  CONTROL_LABEL,
  CONTROL_HIDDEN,
  DATATYPE_TEXT,
  DATATYPE_INTEGER,
  DATATYPE_DECIMAL,
  DATATYPE_DATE,
  DATATYPE_TIME,
  DATATYPE_DATETIME,
  DATATYPE_GEOPOINT,
  DATATYPE_GEOSHAPE,
  DATATYPE_GEOTRACE,
  DATATYPE_BINARY,
  DATATYPE_BARCODE,
} from '@/stores/webForm'

interface Props {
  question: QuestionResponse
  value: any
  disabled?: boolean
  externalError?: string | null
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (event: 'answer', value: unknown): void
}>()

const webFormStore = useWebFormStore()
const controlError = ref<string | null>(null)
const localValidationError = ref<string | null>(null)
const uploadingFile = ref(false)
const dirty = ref(false)
const promptAudioUrl = ref<string | null>(null)
const promptImageUrl = ref<string | null>(null)
const promptVideoUrl = ref<string | null>(null)
const promptMediaError = ref<string | null>(null)
let promptMediaLoadGeneration = 0

function revokePromptMediaUrls() {
  for (const url of [promptAudioUrl.value, promptImageUrl.value, promptVideoUrl.value]) {
    if (url) URL.revokeObjectURL(url)
  }
  promptAudioUrl.value = null
  promptImageUrl.value = null
  promptVideoUrl.value = null
}

async function fetchPromptMedia(fileName: string | null | undefined): Promise<string | null> {
  if (!fileName) return null
  const formVersionId = webFormStore.currentSession?.formVersionId
    || webFormStore.formDefinition?.formVersionId
  if (!formVersionId) return null

  const response = await api.get(
    `/web/forms/${formVersionId}/media/${encodeURIComponent(fileName)}`,
    { responseType: 'blob' },
  )
  return URL.createObjectURL(response.data)
}

async function refreshPromptMedia() {
  const generation = ++promptMediaLoadGeneration
  revokePromptMediaUrls()
  promptMediaError.value = null

  const files = [
    props.question.promptAudioFile,
    props.question.promptImageFile,
    props.question.promptVideoFile,
  ]
  if (!files.some(Boolean)) return

  try {
    const [audio, image, video] = await Promise.all([
      fetchPromptMedia(props.question.promptAudioFile),
      fetchPromptMedia(props.question.promptImageFile),
      fetchPromptMedia(props.question.promptVideoFile),
    ])
    if (generation !== promptMediaLoadGeneration) {
      for (const url of [audio, image, video]) {
        if (url) URL.revokeObjectURL(url)
      }
      return
    }
    promptAudioUrl.value = audio
    promptImageUrl.value = image
    promptVideoUrl.value = video
  } catch (error) {
    console.error('Failed to load prompt media:', error)
    if (generation === promptMediaLoadGeneration) {
      promptMediaError.value = 'Question media could not be loaded.'
    }
  }
}


const semanticType = computed(() => (props.question.questionType || '').toLowerCase())
const hasSemanticType = computed(() => semanticType.value.length > 0)
const appearanceTokens = computed(() =>
  (props.question.appearanceHint || '').toLowerCase().split(/\s+/).filter(Boolean),
)

const isLabel = computed(() => hasSemanticType.value ? semanticType.value === 'note' : props.question.controlType === CONTROL_LABEL)
const isHidden = computed(() => hasSemanticType.value ? semanticType.value === 'hidden' : props.question.controlType === CONTROL_HIDDEN)
const isSelectOne = computed(() => hasSemanticType.value ? semanticType.value === 'select1' : props.question.controlType === CONTROL_SELECT_ONE)
const isSelectMulti = computed(() => hasSemanticType.value ? semanticType.value === 'select' : props.question.controlType === CONTROL_SELECT_MULTI)

const isGeoPoint = computed(() => hasSemanticType.value ? semanticType.value === 'geopoint' : props.question.dataType === DATATYPE_GEOPOINT)
const isGeoShape = computed(() => hasSemanticType.value ? semanticType.value === 'geoshape' : props.question.dataType === DATATYPE_GEOSHAPE)
const isGeoTrace = computed(() => hasSemanticType.value ? semanticType.value === 'geotrace' : props.question.dataType === DATATYPE_GEOTRACE)
const isBarcode = computed(() => hasSemanticType.value ? semanticType.value === 'barcode' : props.question.dataType === DATATYPE_BARCODE)
const isBinary = computed(() => hasSemanticType.value ? semanticType.value === 'binary' : props.question.dataType === DATATYPE_BINARY)

const isText = computed(() =>
  hasSemanticType.value
    ? semanticType.value === 'text' || semanticType.value === 'string'
    : !isLabel.value && !isHidden.value && props.question.dataType === DATATYPE_TEXT,
)

// XLSForm notes compile to readonly string <input> controls rather than a
// dedicated JavaRosa label control. Treat readonly text as display-only
// content so notes/calculated summaries never render as disabled text boxes.
const isDisplayOnlyNote = computed(() =>
  isLabel.value || (props.question.readOnly && isText.value),
)
const isInteger = computed(() => hasSemanticType.value ? ['int', 'integer', 'long'].includes(semanticType.value) : props.question.dataType === DATATYPE_INTEGER)
const isDecimal = computed(() => hasSemanticType.value ? semanticType.value === 'decimal' : props.question.dataType === DATATYPE_DECIMAL)
const isDate = computed(() => hasSemanticType.value ? semanticType.value === 'date' : props.question.dataType === DATATYPE_DATE)
const isTime = computed(() => hasSemanticType.value ? semanticType.value === 'time' : props.question.dataType === DATATYPE_TIME)
const isDateTime = computed(() => hasSemanticType.value ? ['datetime', 'datetime-local', 'datetime'].includes(semanticType.value) : props.question.dataType === DATATYPE_DATETIME)
const isUpload = computed(() => isBinary.value || (!hasSemanticType.value && props.question.controlType === CONTROL_UPLOAD))
const isSignature = computed(() => isBinary.value && appearanceTokens.value.includes('signature'))
const isMultiline = computed(() => isText.value && appearanceTokens.value.includes('multiline'))
const isMinimalSelect = computed(() => isSelectOne.value && appearanceTokens.value.includes('minimal'))

const uploadMediaType = computed(() => (props.question.mediaType || '').trim().toLowerCase())
const uploadAccept = computed(() => {
  if (uploadMediaType.value) return props.question.mediaType || undefined
  return 'image/*,audio/*,video/*,application/pdf'
})
const uploadKind = computed(() => {
  if (isSignature.value) return 'signature'
  if (uploadMediaType.value.startsWith('image/')) return 'image'
  if (uploadMediaType.value.startsWith('audio/')) return 'audio'
  if (uploadMediaType.value.startsWith('video/')) return 'video'
  return 'file'
})

function questionKey(): string {
  return props.question.instancePath || props.question.path || props.question.name
}

function normalizeDate(value: unknown): string {
  const raw = value == null ? '' : String(value).trim()
  if (!raw) return ''
  if (/^\d{4}-\d{2}-\d{2}$/.test(raw)) return raw

  const displayMatch = raw.match(/^(\d{1,2})\/(\d{1,2})\/(\d{2}|\d{4})$/)
  if (displayMatch) {
    const [, day, month, yearText] = displayMatch
    const year = yearText.length === 2 ? 2000 + Number(yearText) : Number(yearText)
    return `${year.toString().padStart(4, '0')}-${month.padStart(2, '0')}-${day.padStart(2, '0')}`
  }

  const isoPrefix = raw.match(/^(\d{4}-\d{2}-\d{2})/)
  return isoPrefix ? isoPrefix[1] : ''
}

function normalizeTime(value: unknown): string {
  const raw = value == null ? '' : String(value).trim()
  if (!raw) return ''
  const match = raw.match(/(\d{1,2}):(\d{2})/)
  return match ? `${match[1].padStart(2, '0')}:${match[2]}` : ''
}

function normalizeDateTime(value: unknown): string {
  const raw = value == null ? '' : String(value).trim()
  if (!raw) return ''

  const iso = raw.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{1,2}:\d{2})/)
  if (iso) return `${iso[1]}T${iso[2].padStart(5, '0')}`

  const display = raw.match(/^(\d{1,2})\/(\d{1,2})\/(\d{2}|\d{4})[ ,T]+(\d{1,2}):(\d{2})/)
  if (display) {
    const [, day, month, yearText, hour, minute] = display
    const year = yearText.length === 2 ? 2000 + Number(yearText) : Number(yearText)
    return `${year.toString().padStart(4, '0')}-${month.padStart(2, '0')}-${day.padStart(2, '0')}T${hour.padStart(2, '0')}:${minute}`
  }

  return ''
}

function normalizeSelectOne(value: unknown): string {
  const raw = value == null ? '' : String(value)
  if (!raw) return ''
  const byValue = props.question.choices?.find((choice) => choice.value === raw)
  if (byValue) return byValue.value
  const byLabel = props.question.choices?.find((choice) => choice.label === raw)
  return byLabel?.value ?? raw
}

function normalizeIncoming(value: unknown): string {
  if (isDate.value) return normalizeDate(value)
  if (isTime.value) return normalizeTime(value)
  if (isDateTime.value) return normalizeDateTime(value)
  if (isSelectOne.value) return normalizeSelectOne(value)
  return value == null ? '' : String(value)
}

const draftValue = ref(normalizeIncoming(props.value))
let activeQuestionKey = questionKey()

watch(
  () => [questionKey(), props.value, props.question.choices] as const,
  ([nextKey, nextValue]) => {
    if (nextKey !== activeQuestionKey) {
      activeQuestionKey = nextKey
      dirty.value = false
      controlError.value = null
      localValidationError.value = null
      draftValue.value = normalizeIncoming(nextValue)
      return
    }

    // Do not overwrite an in-progress local edit with JavaRosa's display text.
    // The local draft is reset when navigation changes the question.
    if (!dirty.value) {
      draftValue.value = normalizeIncoming(nextValue)
    }
  },
  { deep: true },
)

watch(
  () => [
    questionKey(),
    props.question.promptAudioFile,
    props.question.promptImageFile,
    props.question.promptVideoFile,
    webFormStore.currentSession?.formVersionId,
    webFormStore.formDefinition?.formVersionId,
  ] as const,
  () => { void refreshPromptMedia() },
  { immediate: true },
)

onBeforeUnmount(() => {
  promptMediaLoadGeneration += 1
  revokePromptMediaUrls()
})

function updateDraft(event: Event) {
  const target = event.target as HTMLInputElement
  dirty.value = true
  localValidationError.value = null
  draftValue.value = target.value
}

function emitAnswer(value: string) {
  localValidationError.value = null
  dirty.value = false
  emit('answer', value)
}

function commitDraft() {
  if (!dirty.value) return
  emitAnswer(draftValue.value)
}

function emitImmediate(value: string) {
  draftValue.value = value
  emitAnswer(value)
}

function handleSelectChange(event: Event) {
  const target = event.target as HTMLSelectElement
  emitImmediate(target.value)
}

function handleRadioChange(value: string) {
  emitImmediate(value)
}

function clearSelectOne() {
  emitImmediate('')
}

function handleCheckboxChange(event: Event) {
  const target = event.target as HTMLInputElement
  const current = (draftValue.value || '').split(' ').filter(Boolean)
  const next = target.checked
    ? [...new Set([...current, target.value])]
    : current.filter((value) => value !== target.value)
  emitImmediate(next.join(' '))
}

function handleDateChange(event: Event) {
  const target = event.target as HTMLInputElement
  emitImmediate(target.value)
}


function answerIsEmpty(value: unknown): boolean {
  if (value === null || value === undefined) return true
  if (typeof value === 'string') return value.trim().length === 0
  if (Array.isArray(value)) return value.length === 0
  return false
}

function validateLocal(): boolean {
  if (isHidden.value || isLabel.value || props.question.readOnly || props.disabled) {
    localValidationError.value = null
    return true
  }

  if (props.question.required && answerIsEmpty(draftValue.value)) {
    localValidationError.value = 'This field is required.'
    return false
  }

  localValidationError.value = null
  return true
}

/**
 * Commit any text/number draft before the parent navigates away. Vue emits are
 * synchronous, so the parent can immediately append the resulting API request
 * to its serialized save queue and then await that queue.
 */
function flush(validate = true): boolean {
  if (validate && !validateLocal()) return false
  if (dirty.value) commitDraft()
  return true
}

function focusInput() {
  const element = document.querySelector(`[data-question-key="${CSS.escape(questionKey())}"] input, [data-question-key="${CSS.escape(questionKey())}"] select, [data-question-key="${CSS.escape(questionKey())}"] textarea`) as HTMLElement | null
  element?.focus()
}

defineExpose({ flush, validateLocal, focusInput })

const displayedError = computed(() => {
  if (props.externalError) return props.externalError
  if (localValidationError.value) return localValidationError.value
  if (controlError.value) return controlError.value
  if (props.question.validationStatus === 'invalid') {
    return props.question.constraintMessage || props.question.constraintText || 'Please check this field.'
  }
  return null
})

async function uploadBytes(fileName: string, contentType: string, bytes: number[]) {
  const sessionId = webFormStore.currentSession?.id
  if (!sessionId) {
    throw new Error('The form session is not available. Please reload the form and try again.')
  }

  await webFormStore.uploadAttachment(sessionId, {
    questionPath: props.question.path || props.question.name,
    fileName,
    contentType: contentType || 'application/octet-stream',
    fileSize: bytes.length,
    content: bytes,
  })

  // Binary XForm answers store the attachment file name. The bytes are stored separately.
  emitImmediate(fileName)
}

async function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return

  uploadingFile.value = true
  controlError.value = null

  try {
    const bytes = Array.from(new Uint8Array(await file.arrayBuffer()))
    await uploadBytes(file.name, file.type || 'application/octet-stream', bytes)
  } catch (error) {
    console.error('Failed to upload web-form attachment:', error)
    controlError.value = error instanceof Error
      ? error.message
      : 'The file could not be uploaded. Please try again.'
    target.value = ''
  } finally {
    uploadingFile.value = false
  }
}

function handleGeoAnswer(value: string) {
  emitImmediate(value)
}

async function handleSignatureSave(payload: { fileName: string; contentType: string; bytes: number[] }) {
  uploadingFile.value = true
  controlError.value = null
  try {
    await uploadBytes(payload.fileName, payload.contentType, payload.bytes)
  } catch (error) {
    console.error('Failed to upload signature:', error)
    controlError.value = error instanceof Error
      ? error.message
      : 'The signature could not be uploaded. Please try again.'
  } finally {
    uploadingFile.value = false
  }
}
</script>

<template>
  <div
    class="web-form-question"
    :class="{ 'question-required': question.required, 'question-invalid': !!displayedError }"
    :data-question-key="questionKey()"
  >
    <label v-if="!isHidden && !isDisplayOnlyNote" class="question-label">
      <span class="label-text">{{ question.label || question.name }}</span>
      <span v-if="question.required" class="required-indicator" aria-label="required">*</span>
    </label>

    <p v-if="question.hint && !isHidden && !isDisplayOnlyNote" class="question-hint">{{ question.hint }}</p>

    <div
      v-if="promptAudioUrl || promptImageUrl || promptVideoUrl || promptMediaError"
      class="prompt-media"
      aria-label="Question media"
    >
      <img v-if="promptImageUrl" :src="promptImageUrl" class="prompt-image" alt="" />
      <audio v-if="promptAudioUrl" :src="promptAudioUrl" class="prompt-audio" controls preload="metadata"></audio>
      <video v-if="promptVideoUrl" :src="promptVideoUrl" class="prompt-video" controls preload="metadata"></video>
      <p v-if="promptMediaError" class="prompt-media-error" role="status">{{ promptMediaError }}</p>
    </div>

    <!-- Notes are instructions/content, not disabled inputs. -->
    <div v-if="isDisplayOnlyNote" class="label-note">
      <div class="note-text">{{ question.label || question.name }}</div>
      <p v-if="question.hint" class="note-hint">{{ question.hint }}</p>
      <p
        v-if="question.readOnly && draftValue && String(draftValue) !== String(question.label || '')"
        class="note-value"
      >
        {{ draftValue }}
      </p>
    </div>

    <WebFormGeoControl
      v-else-if="isGeoPoint || isGeoTrace || isGeoShape"
      :kind="isGeoPoint ? 'geopoint' : isGeoTrace ? 'geotrace' : 'geoshape'"
      :value="draftValue"
      :disabled="disabled"
      :read-only="question.readOnly"
      :required="question.required"
      @answer="handleGeoAnswer"
    />

    <WebFormBarcode
      v-else-if="isBarcode"
      :value="draftValue"
      :disabled="disabled"
      :read-only="question.readOnly"
      :required="question.required"
      @answer="emitImmediate($event)"
    />

    <WebFormSignature
      v-else-if="isSignature"
      :disabled="disabled || question.readOnly || uploadingFile"
      :existing-file-name="draftValue || null"
      @save="handleSignatureSave"
    />

    <div v-else-if="isUpload || isBinary" class="file-upload">
      <input
        type="file"
        @change="handleFileChange"
        :disabled="disabled || question.readOnly || uploadingFile"
        :required="question.required"
        class="file-input"
        :accept="uploadAccept"
        :aria-label="`${uploadKind} upload`"
        :aria-invalid="!!displayedError"
      />
      <p class="file-kind">
        {{ uploadKind === 'file' ? 'Choose a file' : `Choose or capture ${uploadKind}` }}
      </p>
      <p v-if="uploadingFile" class="file-status">Uploading…</p>
      <p v-else-if="draftValue" class="file-selected">Attached: {{ draftValue }}</p>
    </div>

    <!-- Numbers remain strings while editing so JavaRosa cannot reformat each keystroke. -->
    <input
      v-else-if="isInteger || isDecimal"
      type="text"
      :inputmode="isInteger ? 'numeric' : 'decimal'"
      :value="draftValue"
      @input="updateDraft"
      @change="commitDraft"
      :disabled="disabled || question.readOnly"
      :required="question.required"
      class="form-input"
      :aria-invalid="!!displayedError"
    />

    <textarea
      v-else-if="isMultiline"
      :value="draftValue"
      @input="updateDraft"
      @change="commitDraft"
      :disabled="disabled || question.readOnly"
      :required="question.required"
      class="form-textarea"
      rows="4"
      :aria-invalid="!!displayedError"
    ></textarea>

    <input
      v-else-if="isText"
      type="text"
      :value="draftValue"
      @input="updateDraft"
      @change="commitDraft"
      :disabled="disabled || question.readOnly"
      :required="question.required"
      class="form-input"
      :aria-invalid="!!displayedError"
    />

    <input
      v-else-if="isDate"
      type="date"
      :value="draftValue"
      @change="handleDateChange"
      :disabled="disabled || question.readOnly"
      :required="question.required"
      class="form-input"
      :aria-invalid="!!displayedError"
    />
    <input
      v-else-if="isTime"
      type="time"
      :value="draftValue"
      @change="handleDateChange"
      :disabled="disabled || question.readOnly"
      :required="question.required"
      class="form-input"
      :aria-invalid="!!displayedError"
    />
    <input
      v-else-if="isDateTime"
      type="datetime-local"
      :value="draftValue"
      @change="handleDateChange"
      :disabled="disabled || question.readOnly"
      :required="question.required"
      class="form-input"
      :aria-invalid="!!displayedError"
    />

    <!-- Collect uses a compact/minimal widget only when appearance=minimal;
         the default select-one experience is an immediately visible choice list. -->
    <select
      v-else-if="isSelectOne && isMinimalSelect"
      :value="draftValue"
      @change="handleSelectChange"
      :disabled="disabled || question.readOnly"
      :required="question.required"
      class="form-select"
      :aria-invalid="!!displayedError"
    >
      <option value="">Select one…</option>
      <option v-for="choice in question.choices" :key="choice.value" :value="choice.value">
        {{ choice.label }}
      </option>
    </select>

    <div v-else-if="isSelectOne" class="choice-group" role="radiogroup" :aria-invalid="!!displayedError">
      <label
        v-for="choice in question.choices"
        :key="choice.value"
        class="choice-row"
        :class="{ 'choice-selected': draftValue === choice.value }"
      >
        <input
          type="radio"
          :name="`question-${questionKey()}`"
          :value="choice.value"
          :checked="draftValue === choice.value"
          @change="handleRadioChange(choice.value)"
          :disabled="disabled || question.readOnly"
        />
        <span>{{ choice.label }}</span>
      </label>
      <button
        v-if="draftValue && !question.required"
        type="button"
        class="clear-answer"
        :disabled="disabled || question.readOnly"
        @click="clearSelectOne"
      >
        Clear answer
      </button>
    </div>

    <div v-else-if="isSelectMulti" class="choice-group" role="group" :aria-invalid="!!displayedError">
      <label
        v-for="choice in question.choices"
        :key="choice.value"
        class="choice-row"
        :class="{ 'choice-selected': (draftValue || '').split(' ').includes(choice.value) }"
      >
        <input
          type="checkbox"
          :value="choice.value"
          :checked="(draftValue || '').split(' ').includes(choice.value)"
          @change="handleCheckboxChange"
          :disabled="disabled || question.readOnly"
        />
        <span>{{ choice.label }}</span>
      </label>
    </div>

    <input v-else-if="isHidden" type="hidden" :value="draftValue" />

    <p v-if="displayedError" class="validation-error" role="alert">{{ displayedError }}</p>
  </div>
</template>

<style scoped>
.web-form-question {
  padding: 1rem 0 1.25rem;
  border-bottom: 1px solid #e5e7eb;
}

.web-form-question:last-child {
  border-bottom: 0;
}

.web-form-question.question-invalid {
  margin-left: -0.75rem;
  padding-left: 0.75rem;
  border-left: 3px solid #dc2626;
  background: linear-gradient(90deg, #fef2f2 0, rgba(254, 242, 242, 0) 60%);
}

.question-invalid .form-input,
.question-invalid .form-select,
.question-invalid .form-textarea,
.question-invalid .file-input,
.question-invalid .choice-row {
  border-color: #ef4444;
}

.prompt-media {
  margin: 0.25rem 0 0.9rem;
}

.prompt-image,
.prompt-video {
  display: block;
  max-width: 100%;
  max-height: 28rem;
  border-radius: 6px;
  object-fit: contain;
  background: #f8fafc;
}

.prompt-audio {
  width: min(100%, 32rem);
}

.prompt-media-error {
  margin: 0.35rem 0 0;
  color: #92400e;
  font-size: 0.85rem;
}

.question-label {
  display: block;
  margin-bottom: 0.45rem;
  font-weight: 600;
  font-size: 1rem;
  line-height: 1.45;
  color: #1f2937;
}

.required-indicator {
  color: #dc2626;
  margin-left: 0.25rem;
}

.question-hint,
.note-hint {
  font-size: 0.875rem;
  line-height: 1.45;
  color: #6b7280;
  margin: 0 0 0.75rem;
}

.note-hint {
  margin: 0.4rem 0 0;
}

.form-input,
.form-select,
.form-textarea {
  width: 100%;
  padding: 0.78rem 0.85rem;
  border: 1px solid #b8c0cc;
  border-radius: 6px;
  background: #fff;
  color: #111827;
  font-size: 1rem;
  line-height: 1.35;
  transition: border-color 0.15s, box-shadow 0.15s;
  box-sizing: border-box;
}

.form-textarea {
  min-height: 7rem;
  resize: vertical;
}

.form-input:focus,
.form-select:focus,
.form-textarea:focus {
  outline: none;
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.form-input:disabled,
.form-select:disabled,
.form-textarea:disabled {
  background-color: #f3f4f6;
  color: #6b7280;
}

.choice-group {
  display: flex;
  flex-direction: column;
  gap: 0.55rem;
}

.choice-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  min-height: 48px;
  padding: 0.7rem 0.85rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  color: #1f2937;
  line-height: 1.35;
  transition: border-color 0.15s, background 0.15s;
}

.choice-row:hover {
  border-color: #9ca3af;
  background: #f9fafb;
}

.choice-row.choice-selected {
  border-color: #2563eb;
  background: #eff6ff;
}

.choice-row input[type="radio"],
.choice-row input[type="checkbox"] {
  flex: 0 0 auto;
  width: 1.2rem;
  height: 1.2rem;
  margin: 0;
  accent-color: #2563eb;
}

.clear-answer {
  align-self: flex-start;
  margin-top: 0.2rem;
  padding: 0.25rem 0;
  border: 0;
  background: transparent;
  color: #4b5563;
  text-decoration: underline;
  cursor: pointer;
  font-size: 0.85rem;
}

.file-upload {
  display: flex;
  flex-direction: column;
  gap: 0.45rem;
}

.file-input {
  width: 100%;
  padding: 0.75rem;
  border: 1px dashed #9ca3af;
  border-radius: 6px;
  background: #f9fafb;
  cursor: pointer;
  box-sizing: border-box;
}

.file-kind,
.file-selected,
.file-status {
  margin: 0;
  font-size: 0.84rem;
}

.file-kind,
.file-status { color: #6b7280; }
.file-selected { color: #047857; }

.label-note {
  padding: 0.9rem 1rem;
  border-left: 4px solid #94a3b8;
  border-radius: 4px;
  background: #f8fafc;
  color: #334155;
  line-height: 1.55;
}

.note-value {
  margin: 0.5rem 0 0;
  color: #374151;
  font-weight: 500;
  white-space: pre-wrap;
}

.note-text {
  white-space: pre-wrap;
}

.validation-error {
  margin: 0.55rem 0 0;
  color: #b91c1c;
  font-size: 0.875rem;
  font-weight: 500;
}

@media (max-width: 640px) {
  .choice-row {
    padding: 0.8rem;
  }
}
</style>
