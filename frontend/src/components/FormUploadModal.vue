<script setup lang="ts">
import { computed, ref } from 'vue'
import { useFormStore, type UploadFormResponse } from '@/stores/form'

interface Props { projectId: string }
interface Emits {
  (e: 'uploaded', result: UploadFormResponse): void
  (e: 'close'): void
}

type UploadMode = 'xlsform' | 'xml'

const props = defineProps<Props>()
const emit = defineEmits<Emits>()
const formStore = useFormStore()

const mode = ref<UploadMode>('xlsform')
const file = ref<File | null>(null)
const version = ref('')
const changelog = ref('')
const uploading = ref(false)
const error = ref<string | null>(null)
const uploadResult = ref<UploadFormResponse | null>(null)

const success = computed(() => uploadResult.value?.success === true)
const isXlsxFile = computed(() => !!file.value && file.value.name.toLowerCase().endsWith('.xlsx'))
const isXmlFile = computed(() => !!file.value && file.value.name.toLowerCase().endsWith('.xml'))
const isValidFile = computed(() => mode.value === 'xlsform' ? isXlsxFile.value : isXmlFile.value)
const accept = computed(() => mode.value === 'xlsform'
  ? '.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
  : '.xml,application/xml,text/xml')

function selectMode(next: UploadMode) {
  if (uploading.value || mode.value === next) return
  mode.value = next
  file.value = null
  error.value = null
  uploadResult.value = null
}

function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  file.value = input.files?.[0] || null
  error.value = null
  uploadResult.value = null
}

function validateFile(): boolean {
  if (!file.value) {
    error.value = mode.value === 'xlsform' ? 'Please select an XLSForm .xlsx file' : 'Please select an XForm .xml file'
    return false
  }
  if (!isValidFile.value) {
    error.value = mode.value === 'xlsform' ? 'Only .xlsx XLSForm files are supported' : 'Only .xml XForm files are supported'
    return false
  }
  if (file.value.size > 10 * 1024 * 1024) {
    error.value = 'File size must be 10MB or smaller'
    return false
  }
  return true
}

async function handleUpload() {
  if (!validateFile()) return
  uploading.value = true
  error.value = null
  uploadResult.value = null

  const data = new FormData()
  data.append('file', file.value!)
  if (mode.value === 'xlsform') {
    if (version.value.trim()) data.append('version', version.value.trim())
    if (changelog.value.trim()) data.append('changelog', changelog.value.trim())
  }

  try {
    const result = mode.value === 'xlsform'
      ? await formStore.uploadXlsForm(props.projectId, data)
      : await formStore.uploadXmlForm(props.projectId, data)
    uploadResult.value = result
    emit('uploaded', result)
  } catch (err: any) {
    const response = err.response?.data
    uploadResult.value = response && typeof response === 'object' ? response : null
    error.value = response?.message || formStore.error || 'Upload failed'
  } finally {
    uploading.value = false
  }
}

function handleClose() {
  if (!uploading.value) emit('close')
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
</script>

<template>
  <div class="modal-overlay" @click.self="handleClose">
    <div class="modal" @click.stop>
      <div class="modal-header">
        <div>
          <h2>Upload Form</h2>
          <p>Upload an XLSForm workbook or an existing XForm XML.</p>
        </div>
        <button class="modal-close" @click="handleClose" :disabled="uploading">&times;</button>
      </div>

      <div class="mode-tabs" role="tablist" aria-label="Form upload type">
        <button type="button" :class="{ active: mode === 'xlsform' }" @click="selectMode('xlsform')">
          XLSForm (.xlsx)
        </button>
        <button type="button" :class="{ active: mode === 'xml' }" @click="selectMode('xml')">
          XForm XML
        </button>
      </div>

      <div v-if="!success" class="modal-body">
        <form class="modal-form" @submit.prevent="handleUpload">
          <div class="form-group">
            <label for="form-file">{{ mode === 'xlsform' ? 'XLSForm workbook' : 'XForm XML file' }} <span class="required">*</span></label>
            <input id="form-file" type="file" :accept="accept" @change="handleFileChange" :disabled="uploading" />
            <div v-if="file" class="selected-file">
              <strong>{{ file.name }}</strong><span>{{ formatFileSize(file.size) }}</span>
            </div>
            <p class="help-text" v-if="mode === 'xlsform'">
              The workbook is preflighted, converted with pyxform, then validated with JavaRosa 4.3.2 before it is stored.
            </p>
            <p class="help-text" v-else>Raw XML is validated with JavaRosa before storage.</p>
          </div>

          <template v-if="mode === 'xlsform'">
            <div class="form-group">
              <label for="version">Expected version (optional)</label>
              <input id="version" v-model="version" type="text" maxlength="50" placeholder="e.g. 20260814" :disabled="uploading" />
              <span class="help-text">The version in the XLSForm settings sheet remains authoritative; a mismatch is returned as a warning.</span>
            </div>
            <div class="form-group">
              <label for="changelog">Changelog (optional)</label>
              <textarea id="changelog" v-model="changelog" rows="3" maxlength="1000" :disabled="uploading" />
              <span class="char-count">{{ changelog.length }}/1000</span>
            </div>
          </template>

          <div v-if="error" class="message error-message">{{ error }}</div>
          <div v-if="uploadResult?.errors?.length" class="message error-list">
            <strong>Blocking errors</strong>
            <ul><li v-for="item in uploadResult.errors" :key="item">{{ item }}</li></ul>
          </div>
          <div v-if="uploadResult?.warnings?.length" class="message warning-list">
            <strong>Warnings</strong>
            <ul><li v-for="item in uploadResult.warnings" :key="item">{{ item }}</li></ul>
          </div>

          <div class="modal-actions">
            <button type="button" class="btn btn-secondary" @click="handleClose" :disabled="uploading">Cancel</button>
            <button type="submit" class="btn btn-primary" :disabled="!isValidFile || uploading">
              {{ uploading ? (mode === 'xlsform' ? 'Converting...' : 'Uploading...') : (mode === 'xlsform' ? 'Convert & Upload' : 'Upload XML') }}
            </button>
          </div>
        </form>
      </div>

      <div v-else class="modal-body success-state">
        <div class="success-icon">✓</div>
        <h3>{{ mode === 'xlsform' ? 'XLSForm Converted Successfully' : 'Form Uploaded Successfully' }}</h3>
        <p>{{ uploadResult?.message }}</p>

        <div class="success-details" v-if="uploadResult">
          <div class="detail-row"><span class="label">Form title</span><span>{{ uploadResult.title || 'N/A' }}</span></div>
          <div class="detail-row"><span class="label">Form ID</span><span>{{ uploadResult.xmlns || 'N/A' }}</span></div>
          <div class="detail-row"><span class="label">Version</span><span>{{ uploadResult.version || 'N/A' }}</span></div>
          <div class="detail-row"><span class="label">Form hash</span><span class="hash">{{ uploadResult.formHash || 'N/A' }}</span></div>
        </div>

        <div v-if="uploadResult?.warnings?.length" class="message warning-list result-message">
          <strong>Conversion warnings</strong>
          <ul><li v-for="item in uploadResult.warnings" :key="item">{{ item }}</li></ul>
        </div>
        <div v-if="uploadResult?.info?.length" class="message info-list result-message">
          <strong>Validation information</strong>
          <ul><li v-for="item in uploadResult.info" :key="item">{{ item }}</li></ul>
        </div>
        <div v-if="uploadResult?.extraColumns && Object.keys(uploadResult.extraColumns).length" class="message info-list result-message">
          <strong>Preserved extra columns</strong>
          <div v-for="(columns, sheet) in uploadResult.extraColumns" :key="sheet">
            {{ sheet }}: {{ columns.join(', ') }}
          </div>
        </div>

        <div class="modal-actions"><button class="btn btn-primary" @click="handleClose">Done</button></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.5); display:flex; align-items:center; justify-content:center; padding:24px; z-index:1000; }
.modal { background:white; border-radius:12px; width:100%; max-width:680px; max-height:90vh; overflow:auto; box-shadow:0 20px 25px -5px rgba(0,0,0,.12); }
.modal-header { display:flex; justify-content:space-between; gap:24px; padding:20px 24px 14px; border-bottom:1px solid #e5e7eb; }
.modal-header h2 { margin:0; font-size:20px; color:#1a1a1a; }
.modal-header p { margin:5px 0 0; color:#6b7280; font-size:13px; }
.modal-close { border:0; background:none; font-size:26px; color:#9ca3af; cursor:pointer; }
.mode-tabs { display:flex; gap:8px; padding:16px 24px 0; }
.mode-tabs button { border:1px solid #d1d5db; background:white; padding:8px 14px; border-radius:8px; cursor:pointer; font-weight:600; color:#4b5563; }
.mode-tabs button.active { background:#111827; color:white; border-color:#111827; }
.modal-body { padding:20px 24px 24px; }
.modal-form { display:flex; flex-direction:column; gap:18px; }
.form-group { display:flex; flex-direction:column; gap:7px; }
.form-group label { font-weight:600; color:#374151; }
.required { color:#dc2626; }
input[type='text'], textarea, input[type='file'] { border:1px solid #d1d5db; border-radius:8px; padding:10px 12px; font:inherit; }
.selected-file { display:flex; justify-content:space-between; gap:16px; padding:10px 12px; background:#f9fafb; border-radius:8px; font-size:13px; }
.help-text, .char-count { color:#6b7280; font-size:12px; }
.char-count { text-align:right; }
.message { border-radius:8px; padding:11px 13px; font-size:13px; }
.message ul { margin:6px 0 0; padding-left:20px; }
.error-message, .error-list { background:#fef2f2; color:#991b1b; border:1px solid #fecaca; }
.warning-list { background:#fffbeb; color:#92400e; border:1px solid #fde68a; }
.info-list { background:#eff6ff; color:#1e40af; border:1px solid #bfdbfe; }
.modal-actions { display:flex; justify-content:flex-end; gap:10px; margin-top:4px; }
.btn { border-radius:8px; padding:9px 16px; border:1px solid transparent; font-weight:600; cursor:pointer; }
.btn:disabled { opacity:.55; cursor:not-allowed; }
.btn-primary { background:#111827; color:white; }
.btn-secondary { background:white; border-color:#d1d5db; color:#374151; }
.success-state { text-align:center; }
.success-icon { width:52px; height:52px; border-radius:50%; display:grid; place-items:center; margin:0 auto 12px; background:#ecfdf5; color:#047857; font-size:28px; }
.success-details { text-align:left; border:1px solid #e5e7eb; border-radius:10px; overflow:hidden; margin:18px 0; }
.detail-row { display:grid; grid-template-columns:140px 1fr; gap:12px; padding:9px 12px; border-bottom:1px solid #f3f4f6; }
.detail-row:last-child { border-bottom:0; }
.detail-row .label { font-weight:600; color:#4b5563; }
.hash { word-break:break-all; font-family:monospace; font-size:12px; }
.result-message { text-align:left; margin:12px 0; }
</style>
