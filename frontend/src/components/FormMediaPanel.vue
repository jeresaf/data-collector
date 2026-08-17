<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import api from '@/services/api'
import type { FormVersionSummary } from '@/stores/formLifecycle'

interface Props {
  formId: string
  versions: FormVersionSummary[]
  currentVersionId?: string | null
  canManage?: boolean
}

interface FormAttachment {
  id: string
  fileName: string
  contentType?: string | null
  fileSize?: number | null
  contentHash?: string | null
  isEncrypted?: boolean | null
  formVersionId: string
  formVersionVersion?: string | null
  createdByUsername?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  currentVersionId: null,
  canManage: false,
})

const selectedVersionId = ref('')
const attachments = ref<FormAttachment[]>([])
const selectedFile = ref<File | null>(null)
const loading = ref(false)
const uploading = ref(false)
const error = ref('')
const notice = ref('')

const selectedVersion = computed(() =>
  props.versions.find((version) => version.id === selectedVersionId.value) || null,
)

const totalSize = computed(() =>
  attachments.value.reduce((total, attachment) => total + Number(attachment.fileSize || 0), 0),
)

function preferredVersionId(): string {
  if (props.currentVersionId && props.versions.some((version) => version.id === props.currentVersionId)) {
    return props.currentVersionId
  }
  return props.versions.find((version) => version.isLatest)?.id || props.versions[0]?.id || ''
}

function messageFor(err: any, fallback: string): string {
  return err?.response?.data?.message || err?.response?.data?.error || fallback
}

function attachmentBase(versionId = selectedVersionId.value): string {
  return `/forms/${props.formId}/versions/${versionId}/attachments`
}

function formatBytes(value?: number | null): string {
  const bytes = Number(value || 0)
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const amount = bytes / Math.pow(1024, exponent)
  return `${amount >= 10 || exponent === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[exponent]}`
}

function formatDate(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

async function loadAttachments(): Promise<void> {
  if (!selectedVersionId.value) {
    attachments.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    const response = await api.get<FormAttachment[]>(`${attachmentBase()}/all`)
    attachments.value = response.data
  } catch (err: any) {
    attachments.value = []
    error.value = messageFor(err, 'Unable to load form media.')
  } finally {
    loading.value = false
  }
}

function chooseFile(event: Event): void {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] || null
  notice.value = ''
  error.value = ''
}

async function upload(): Promise<void> {
  if (!props.canManage || !selectedVersionId.value || !selectedFile.value) return
  uploading.value = true
  error.value = ''
  notice.value = ''
  try {
    const formData = new FormData()
    formData.append('file', selectedFile.value)
    formData.append('fileName', selectedFile.value.name)
    formData.append('storageDriver', 'local')
    formData.append('isEncrypted', 'false')

    await api.post(attachmentBase(), formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    notice.value = `${selectedFile.value.name} uploaded to version ${selectedVersion.value?.version || ''}.`
    selectedFile.value = null
    const input = document.getElementById('form-media-file') as HTMLInputElement | null
    if (input) input.value = ''
    await loadAttachments()
  } catch (err: any) {
    error.value = messageFor(err, 'Unable to upload form media.')
  } finally {
    uploading.value = false
  }
}

async function download(attachment: FormAttachment): Promise<void> {
  error.value = ''
  try {
    const response = await api.get(`${attachmentBase(attachment.formVersionId)}/${attachment.id}/download`, {
      responseType: 'blob',
    })
    const url = URL.createObjectURL(response.data instanceof Blob ? response.data : new Blob([response.data]))
    const link = document.createElement('a')
    link.href = url
    link.download = attachment.fileName
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (err: any) {
    error.value = messageFor(err, 'Unable to download form media.')
  }
}

async function remove(attachment: FormAttachment): Promise<void> {
  if (!props.canManage) return
  if (!window.confirm(`Delete ${attachment.fileName} from this form version?`)) return
  error.value = ''
  notice.value = ''
  try {
    await api.delete(`${attachmentBase(attachment.formVersionId)}/${attachment.id}`)
    notice.value = `${attachment.fileName} deleted.`
    await loadAttachments()
  } catch (err: any) {
    error.value = messageFor(err, 'Unable to delete form media.')
  }
}

watch(
  () => [props.currentVersionId, props.versions.map((version) => version.id).join('|')],
  () => {
    if (!selectedVersionId.value || !props.versions.some((version) => version.id === selectedVersionId.value)) {
      selectedVersionId.value = preferredVersionId()
    }
  },
  { immediate: true },
)

watch(selectedVersionId, () => {
  void loadAttachments()
})

onMounted(() => {
  if (!selectedVersionId.value) selectedVersionId.value = preferredVersionId()
  void loadAttachments()
})
</script>

<template>
  <div class="media-panel">
    <div class="media-heading">
      <div>
        <h2>Form media</h2>
        <p>Upload images, audio, video, CSV or other supporting files required by a specific XForm version.</p>
      </div>
      <div class="summary">
        <strong>{{ attachments.length }}</strong> files · {{ formatBytes(totalSize) }}
      </div>
    </div>

    <div v-if="!versions.length" class="empty-state">
      This form does not have a stored version yet. Upload or create a form version before adding media.
    </div>

    <template v-else>
      <div class="version-row">
        <label>
          Form version
          <select v-model="selectedVersionId">
            <option v-for="version in versions" :key="version.id" :value="version.id">
              Version {{ version.version }}{{ version.isPublished ? ' — published' : version.isLatest ? ' — latest' : '' }}
            </option>
          </select>
        </label>
        <div class="version-note">
          Media is stored against the selected version. The filename should exactly match the filename referenced by the XForm.
        </div>
      </div>

      <div v-if="canManage" class="upload-card">
        <label class="file-picker">
          Media file
          <input
            id="form-media-file"
            type="file"
            accept=".png,.jpg,.jpeg,.gif,.wav,.mp3,.mp4,.3gp,.amr,.csv,.json,.geojson,.xml,.xlsx,.xls"
            @change="chooseFile"
          />
        </label>
        <div class="selected-file">
          <template v-if="selectedFile">
            <strong>{{ selectedFile.name }}</strong>
            <span>{{ formatBytes(selectedFile.size) }} · {{ selectedFile.type || 'unknown type' }}</span>
          </template>
          <span v-else>Select the file referenced by your form.</span>
        </div>
        <button class="btn primary" type="button" :disabled="!selectedFile || uploading" @click="upload">
          {{ uploading ? 'Uploading…' : 'Upload media' }}
        </button>
      </div>

      <p v-if="error" class="notice error">{{ error }}</p>
      <p v-if="notice" class="notice success">{{ notice }}</p>

      <div v-if="loading" class="empty-state">Loading media…</div>
      <div v-else-if="!attachments.length" class="empty-state">
        No media files are attached to version {{ selectedVersion?.version || '—' }}.
      </div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Filename</th>
              <th>Type</th>
              <th>Size</th>
              <th>Uploaded</th>
              <th>Hash</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="attachment in attachments" :key="attachment.id">
              <td><strong>{{ attachment.fileName }}</strong></td>
              <td>{{ attachment.contentType || '—' }}</td>
              <td>{{ formatBytes(attachment.fileSize) }}</td>
              <td>
                {{ formatDate(attachment.createdAt) }}
                <small v-if="attachment.createdByUsername">by {{ attachment.createdByUsername }}</small>
              </td>
              <td><code>{{ attachment.contentHash || '—' }}</code></td>
              <td class="actions">
                <button type="button" class="link-button" @click="download(attachment)">Download</button>
                <button v-if="canManage" type="button" class="link-button danger" @click="remove(attachment)">Delete</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.media-panel { display: grid; gap: 18px; }
.media-heading { display: flex; justify-content: space-between; gap: 18px; align-items: flex-start; }
h2, p { margin-top: 0; }
h2 { margin-bottom: 5px; font-size: 20px; color: #111827; }
.media-heading p, .version-note { margin-bottom: 0; color: #6b7280; font-size: 14px; }
.summary { white-space: nowrap; border: 1px solid #e5e7eb; border-radius: 8px; padding: 8px 11px; color: #4b5563; font-size: 13px; }
.summary strong { color: #111827; }
.version-row { display: grid; grid-template-columns: minmax(220px, 340px) 1fr; gap: 18px; align-items: end; }
.version-row label, .file-picker { display: grid; gap: 6px; font-size: 13px; font-weight: 700; color: #374151; }
select, input[type="file"] { width: 100%; box-sizing: border-box; border: 1px solid #d1d5db; border-radius: 7px; padding: 9px 10px; background: white; font: inherit; }
.upload-card { display: grid; grid-template-columns: minmax(260px, 1fr) minmax(220px, 1fr) auto; gap: 14px; align-items: end; border: 1px solid #bfdbfe; background: #eff6ff; border-radius: 10px; padding: 16px; }
.selected-file { min-height: 42px; display: flex; flex-direction: column; justify-content: center; color: #6b7280; font-size: 13px; }
.selected-file strong { color: #111827; overflow-wrap: anywhere; }
.btn { border: 1px solid transparent; border-radius: 7px; padding: 10px 14px; font: inherit; font-weight: 700; cursor: pointer; }
.btn.primary { background: #2563eb; color: white; }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.notice { margin: 0; border-radius: 7px; padding: 10px 12px; font-size: 14px; }
.notice.error { color: #991b1b; background: #fef2f2; }
.notice.success { color: #166534; background: #f0fdf4; }
.empty-state { text-align: center; color: #6b7280; border: 1px dashed #d1d5db; border-radius: 9px; padding: 28px 16px; }
.table-wrap { overflow-x: auto; }
table { width: 100%; border-collapse: collapse; min-width: 820px; }
th, td { border-bottom: 1px solid #e5e7eb; padding: 10px; text-align: left; vertical-align: top; font-size: 13px; }
th { color: #6b7280; background: #f9fafb; }
td small { display: block; color: #6b7280; margin-top: 2px; }
td code { color: #4b5563; font-size: 11px; overflow-wrap: anywhere; }
.actions { white-space: nowrap; }
.link-button { border: 0; background: transparent; padding: 0; margin-right: 12px; color: #2563eb; font: inherit; font-weight: 600; cursor: pointer; }
.link-button.danger { color: #be123c; }
@media (max-width: 760px) {
  .media-heading, .version-row { display: flex; flex-direction: column; align-items: stretch; }
  .summary { align-self: flex-start; }
  .upload-card { grid-template-columns: 1fr; align-items: stretch; }
}
</style>
