<script setup lang="ts">
import { computed, ref } from 'vue'
import WebFormGeoControl from './WebFormGeoControl.vue'
import WebFormSignature from './WebFormSignature.vue'
import WebFormBarcode from './WebFormBarcode.vue'
import { useWebFormStore, type QuestionResponse, CONTROL_SELECT_ONE, CONTROL_SELECT_MULTI, CONTROL_UPLOAD, CONTROL_LABEL, CONTROL_HIDDEN, DATATYPE_TEXT, DATATYPE_INTEGER, DATATYPE_DECIMAL, DATATYPE_DATE, DATATYPE_TIME, DATATYPE_DATETIME, DATATYPE_GEOPOINT, DATATYPE_GEOSHAPE, DATATYPE_GEOTRACE, DATATYPE_BINARY, DATATYPE_BARCODE } from '@/stores/webForm'

interface Props {
  question: QuestionResponse
  value: any
  disabled?: boolean
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (event: 'answer', value: unknown): void
}>()
const webFormStore = useWebFormStore()
const controlError = ref<string | null>(null)
const uploadingFile = ref(false)

const isText = computed(() => props.question.dataType === DATATYPE_TEXT)
const isInteger = computed(() => props.question.dataType === DATATYPE_INTEGER)
const isDecimal = computed(() => props.question.dataType === DATATYPE_DECIMAL)
const isDate = computed(() => props.question.dataType === DATATYPE_DATE)
const isTime = computed(() => props.question.dataType === DATATYPE_TIME)
const isDateTime = computed(() => props.question.dataType === DATATYPE_DATETIME)
const isGeoPoint = computed(() => props.question.dataType === DATATYPE_GEOPOINT)
const isGeoShape = computed(() => props.question.dataType === DATATYPE_GEOSHAPE)
const isGeoTrace = computed(() => props.question.dataType === DATATYPE_GEOTRACE)
const isBinary = computed(() => props.question.dataType === DATATYPE_BINARY)
const isBarcode = computed(() => props.question.dataType === DATATYPE_BARCODE)

const isSelectOne = computed(() => props.question.controlType === CONTROL_SELECT_ONE)
const isSelectMulti = computed(() => props.question.controlType === CONTROL_SELECT_MULTI)
const isUpload = computed(() => props.question.controlType === CONTROL_UPLOAD)
const appearanceTokens = computed(() => (props.question.appearanceHint || '').toLowerCase().split(/\s+/).filter(Boolean))
const uploadMediaType = computed(() => (props.question.mediaType || '').trim().toLowerCase())
const uploadAccept = computed(() => {
  if (uploadMediaType.value) return props.question.mediaType || undefined
  return 'image/*,audio/*,video/*,application/pdf'
})
const isSignature = computed(() => isUpload.value && appearanceTokens.value.includes('signature'))
const uploadKind = computed(() => {
  if (uploadMediaType.value.startsWith('image/')) return 'image'
  if (uploadMediaType.value.startsWith('audio/')) return 'audio'
  if (uploadMediaType.value.startsWith('video/')) return 'video'
  return 'file'
})
const isLabel = computed(() => props.question.controlType === CONTROL_LABEL)
const isHidden = computed(() => props.question.controlType === CONTROL_HIDDEN)

const inputValue = computed({
  get: () => props.value ?? '',
  set: (val) => emit('answer', val)
})

function handleInput(event: Event) {
  const target = event.target as HTMLInputElement
  inputValue.value = target.value
}

function handleSelectChange(event: Event) {
  const target = event.target as HTMLSelectElement
  if (isSelectMulti.value) {
    const selected = Array.from(target.selectedOptions).map(opt => opt.value)
    inputValue.value = selected.join(' ')
  } else {
    inputValue.value = target.value
  }
}

function handleCheckboxChange(event: Event) {
  const target = event.target as HTMLInputElement
  const current = (inputValue.value || '').split(' ').filter(Boolean)
  if (target.checked) {
    inputValue.value = [...current, target.value].join(' ')
  } else {
    inputValue.value = current.filter(v => v !== target.value).join(' ')
  }
}

function handleDateChange(event: Event) {
  const target = event.target as HTMLInputElement
  inputValue.value = target.value
}

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

  // ODK/XForms binary answers contain the attachment filename, not the file
  // contents. The bytes are stored separately by the attachment endpoint.
  inputValue.value = fileName
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
  inputValue.value = value
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
  <div class="web-form-question" :class="{ 'question-required': question.required }">
    <!-- Label -->
    <label v-if="!isHidden && !isLabel" class="question-label">
      <span class="label-text">{{ question.label }}</span>
      <span v-if="question.required" class="required-indicator" aria-hidden="true">*</span>
    </label>

    <!-- Hint -->
    <p v-if="question.hint && !isHidden" class="question-hint">{{ question.hint }}</p>

    <!-- Constraint text -->
    <p v-if="question.constraintText && !isHidden" class="constraint-text">{{ question.constraintText }}</p>

    <!-- Text/Integer/Decimal Input -->
    <input
      v-if="isText || isInteger || isDecimal"
      :type="isInteger ? 'number' : isDecimal ? 'number' : 'text'"
      :step="isDecimal ? 'any' : isInteger ? '1' : undefined"
      :value="inputValue"
      @input="handleInput"
      :placeholder="question.hint"
      :disabled="disabled || question.readOnly"
      :required="question.required"
      class="form-input"
      :aria-invalid="question.validationStatus === 'invalid'"
    />

    <!-- Date/Time/DateTime Input -->
    <input
      v-else-if="isDate"
      type="date"
      :value="inputValue"
      @change="handleDateChange"
      :disabled="disabled || question.readOnly"
      :required="question.required"
      class="form-input"
    />
    <input
      v-else-if="isTime"
      type="time"
      :value="inputValue"
      @change="handleDateChange"
      :disabled="disabled || question.readOnly"
      :required="question.required"
      class="form-input"
    />
    <input
      v-else-if="isDateTime"
      type="datetime-local"
      :value="inputValue"
      @change="handleDateChange"
      :disabled="disabled || question.readOnly"
      :required="question.required"
      class="form-input"
    />

    <!-- Select One -->
    <select
      v-else-if="isSelectOne"
      :value="inputValue"
      @change="handleSelectChange"
      :disabled="disabled || question.readOnly"
      :required="question.required"
      class="form-select"
    >
      <option value="" disabled>Select...</option>
      <option v-for="choice in question.choices" :key="choice.value" :value="choice.value">
        {{ choice.label }}
      </option>
    </select>

    <!-- Select Multiple -->
    <div v-else-if="isSelectMulti" class="checkbox-group">
      <label v-for="choice in question.choices" :key="choice.value" class="checkbox-label">
        <input
          type="checkbox"
          :value="choice.value"
          :checked="(inputValue || '').split(' ').includes(choice.value)"
          @change="handleCheckboxChange"
          :disabled="disabled || question.readOnly"
        />
        <span>{{ choice.label }}</span>
      </label>
    </div>

    <!-- Signature (ODK image question with appearance=signature) -->
    <WebFormSignature
      v-else-if="isSignature"
      :disabled="disabled || question.readOnly || uploadingFile"
      :existing-file-name="inputValue || null"
      @save="handleSignatureSave"
    />

    <!-- File Upload -->
    <div v-else-if="isUpload || isBinary" class="file-upload">
      <input
        type="file"
        @change="handleFileChange"
        :disabled="disabled || question.readOnly || uploadingFile"
        :required="question.required"
        class="file-input"
        :accept="uploadAccept"
        :aria-label="`${uploadKind} upload`"
      />
      <p class="file-kind">{{ uploadKind === 'file' ? 'File' : uploadKind.charAt(0).toUpperCase() + uploadKind.slice(1) }} upload</p>
      <p v-if="uploadingFile" class="file-status">Uploading file...</p>
      <p v-else-if="inputValue" class="file-selected">Selected: {{ inputValue }}</p>
    </div>

    <!-- Label/Note (read-only) -->
    <div v-else-if="isLabel" class="label-note">
      {{ question.label }}
    </div>

    <!-- GeoPoint / GeoTrace / GeoShape -->
    <WebFormGeoControl
      v-else-if="isGeoPoint || isGeoTrace || isGeoShape"
      :kind="isGeoPoint ? 'geopoint' : isGeoTrace ? 'geotrace' : 'geoshape'"
      :value="inputValue"
      :disabled="disabled"
      :read-only="question.readOnly"
      :required="question.required"
      @answer="handleGeoAnswer"
    />

    <!-- Barcode -->
    <WebFormBarcode
      v-else-if="isBarcode"
      :value="inputValue"
      :disabled="disabled"
      :read-only="question.readOnly"
      :required="question.required"
      @answer="inputValue = $event"
    />

    <!-- Hidden -->
    <input v-else-if="isHidden" type="hidden" :value="inputValue" @input="handleInput" />

    <p v-if="controlError" class="validation-error" role="alert">{{ controlError }}</p>

    <!-- Validation message -->
    <p v-if="question.validationStatus === 'invalid' && question.constraintMessage" class="validation-error">
      {{ question.constraintMessage }}
    </p>
  </div>
</template>

<style scoped>
.web-form-question {
  margin-bottom: 1.5rem;
  padding: 1rem;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 1px 3px rgba(0,0,0,0.1);
}

.question-label {
  display: block;
  margin-bottom: 0.5rem;
  font-weight: 500;
  font-size: 1rem;
  color: #1f2937;
}

.required-indicator {
  color: #ef4444;
  margin-left: 0.25rem;
}

.question-hint {
  font-size: 0.875rem;
  color: #6b7280;
  margin-bottom: 0.5rem;
}

.constraint-text {
  font-size: 0.875rem;
  color: #f59e0b;
  margin-bottom: 0.5rem;
}

.form-input,
.form-select,
.form-textarea {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 1rem;
  transition: border-color 0.2s, box-shadow 0.2s;
  box-sizing: border-box;
}

.form-input:focus,
.form-select:focus,
.form-textarea:focus {
  outline: none;
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

.form-input:disabled,
.form-select:disabled,
.form-textarea:disabled {
  background-color: #f9fafb;
  color: #9ca3af;
}

.checkbox-group {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.checkbox-label {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  cursor: pointer;
  font-size: 1rem;
  color: #1f2937;
}

.checkbox-label input[type="checkbox"] {
  width: 1.25rem;
  height: 1.25rem;
  accent-color: #3b82f6;
}

.file-kind {
  margin: 0.4rem 0 0;
  font-size: 0.8rem;
  color: #6b7280;
}

.file-upload {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.file-input {
  padding: 0.5rem;
  border: 2px dashed #d1d5db;
  border-radius: 6px;
  background: #f9fafb;
  cursor: pointer;
}

.file-input:hover {
  border-color: #3b82f6;
}

.file-selected,
.file-status {
  font-size: 0.875rem;
  margin: 0;
}

.file-selected {
  color: #059669;
}

.file-status {
  color: #4b5563;
}

.label-note {
  padding: 0.75rem;
  background: #f3f4f6;
  border-radius: 6px;
  color: #374151;
}

.validation-error {
  color: #ef4444;
  font-size: 0.875rem;
  margin-top: 0.5rem;
  margin-bottom: 0;
}

.question-required .label-text::after {
  content: ' *';
  color: #ef4444;
}
</style>