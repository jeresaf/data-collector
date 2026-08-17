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

interface FormExternalDataset {
  id: string
  datasetId: string
  title: string
  description?: string | null
  contentType?: string | null
  storageDriver?: string | null
  contentHash?: string | null
  fileSize?: number | null
  rowCount?: number | null
  isActive?: boolean | null
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
const datasets = ref<FormExternalDataset[]>([])
const selectedFile = ref<File | null>(null)
const datasetId = ref('')
const title = ref('')
const description = ref('')
const loading = ref(false)
const uploading = ref(false)
const error = ref('')
const notice = ref('')

const selectedVersion = computed(() =>
  props.versions.find((version) => version.id === selectedVersionId.value) || null,
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

function datasetBase(versionId = selectedVersionId.value): string {
  return `/forms/${props.formId}/versions/${versionId}/external-datasets`
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

async function loadDatasets(): Promise<void> {
  if (!selectedVersionId.value) {
    datasets.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    const response = await api.get<FormExternalDataset[]>(`${datasetBase()}/all`)
    datasets.value = response.data
  } catch (err: any) {
    datasets.value = []
    error.value = messageFor(err, 'Unable to load external datasets.')
  } finally {
    loading.value = false
  }
}

function chooseFile(event: Event): void {
  const input = event.target as HTMLInputElement
  selectedFile.value = input.files?.[0] || null
  error.value = ''
  notice.value = ''

  if (selectedFile.value) {
    datasetId.value = selectedFile.value.name
    title.value = selectedFile.value.name
  } else {
    datasetId.value = ''
    title.value = ''
  }
}

function resetUploadForm(): void {
  selectedFile.value = null
  datasetId.value = ''
  title.value = ''
  description.value = ''
  const input = document.getElementById('external-dataset-file') as HTMLInputElement | null
  if (input) input.value = ''
}

async function upload(): Promise<void> {
  if (!props.canManage || !selectedVersionId.value || !selectedFile.value) return
  if (!datasetId.value.trim() || !title.value.trim()) {
    error.value = 'Dataset ID and title are required.'
    return
  }

  uploading.value = true
  error.value = ''
  notice.value = ''
  try {
    const formData = new FormData()
    formData.append('file', selectedFile.value)
    formData.append('datasetId', datasetId.value.trim())
    formData.append('title', title.value.trim())
    formData.append('description', description.value.trim())
    formData.append('storageDriver', 'local')
    formData.append('isActive', 'true')

    await api.post(datasetBase(), formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })

    notice.value = `${datasetId.value.trim()} uploaded and activated for version ${selectedVersion.value?.version || ''}.`
    resetUploadForm()
    await loadDatasets()
  } catch (err: any) {
    error.value = messageFor(err, 'Unable to upload external dataset.')
  } finally {
    uploading.value = false
  }
}

async function download(dataset: FormExternalDataset): Promise<void> {
  error.value = ''
  try {
    const response = await api.get(
      `${datasetBase(dataset.formVersionId)}/${dataset.id}/download`,
      { responseType: 'blob' },
    )
    const url = URL.createObjectURL(response.data instanceof Blob ? response.data : new Blob([response.data]))
    const link = document.createElement('a')
    link.href = url
    link.download = dataset.datasetId
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (err: any) {
    error.value = messageFor(err, 'Unable to download external dataset.')
  }
}

async function remove(dataset: FormExternalDataset): Promise<void> {
  if (!props.canManage) return
  if (!window.confirm(`Delete external dataset ${dataset.datasetId} from this form version?`)) return

  error.value = ''
  notice.value = ''
  try {
    await api.delete(`${datasetBase(dataset.formVersionId)}/${dataset.id}`)
    notice.value = `${dataset.datasetId} deleted.`
    await loadDatasets()
  } catch (err: any) {
    error.value = messageFor(err, 'Unable to delete external dataset.')
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
  resetUploadForm()
  void loadDatasets()
})

onMounted(() => {
  if (!selectedVersionId.value) selectedVersionId.value = preferredVersionId()
  void loadDatasets()
})
</script>

<template>
  <div class="external-data-panel">
    <div class="heading">
      <div>
        <h2>External data</h2>
        <p>
          Upload form-version lookup datasets referenced by XForm secondary instances or functions such as
          <code>instance()</code> and <code>pulldata()</code>.
        </p>
      </div>
      <div class="summary"><strong>{{ datasets.length }}</strong> datasets</div>
    </div>

    <div class="guidance">
      <strong>Important:</strong>
      the Dataset ID must exactly match the filename or dataset name referenced by the XForm, for example
      <code>farm_registry.csv</code> or <code>soil_types.xml</code>. The current web runtime supports CSV and XML lookup data.
    </div>

    <div v-if="!versions.length" class="empty-state">
      This form does not have a stored version yet. Create a form version before adding external data.
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
          External datasets are stored against the selected form version. Upload them to the same version that users fill.
        </div>
      </div>

      <div v-if="canManage" class="upload-card">
        <label class="field file-field">
          Dataset file
          <input
            id="external-dataset-file"
            type="file"
            accept=".csv,.xml,.json,.geojson,.xlsx,.xls"
            @change="chooseFile"
          />
        </label>

        <label class="field">
          Dataset ID
          <input v-model="datasetId" type="text" placeholder="farm_registry.csv" />
        </label>

        <label class="field">
          Title
          <input v-model="title" type="text" placeholder="Farm registry" />
        </label>

        <label class="field description-field">
          Description <span>(optional)</span>
          <input v-model="description" type="text" placeholder="Lookup data used by this XForm version" />
        </label>

        <div class="selected-file">
          <template v-if="selectedFile">
            <strong>{{ selectedFile.name }}</strong>
            <span>{{ formatBytes(selectedFile.size) }} · {{ selectedFile.type || 'unknown type' }}</span>
          </template>
          <span v-else>Select an external data file.</span>
        </div>

        <button
          class="btn primary"
          type="button"
          :disabled="!selectedFile || !datasetId.trim() || !title.trim() || uploading"
          @click="upload"
        >
          {{ uploading ? 'Uploading…' : 'Upload external data' }}
        </button>
      </div>

      <p v-if="error" class="notice error">{{ error }}</p>
      <p v-if="notice" class="notice success">{{ notice }}</p>

      <div v-if="loading" class="empty-state">Loading external data…</div>
      <div v-else-if="!datasets.length" class="empty-state">
        No external datasets are uploaded for version {{ selectedVersion?.version || '—' }}.
      </div>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Dataset ID</th>
              <th>Title</th>
              <th>Type</th>
              <th>Size</th>
              <th>Status</th>
              <th>Uploaded</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="dataset in datasets" :key="dataset.id">
              <td><strong>{{ dataset.datasetId }}</strong></td>
              <td>{{ dataset.title }}</td>
              <td>{{ dataset.contentType || '—' }}</td>
              <td>{{ formatBytes(dataset.fileSize) }}</td>
              <td><span class="status" :class="dataset.isActive ? 'active' : 'inactive'">{{ dataset.isActive ? 'Active' : 'Inactive' }}</span></td>
              <td>
                {{ formatDate(dataset.createdAt) }}
                <small v-if="dataset.createdByUsername">by {{ dataset.createdByUsername }}</small>
              </td>
              <td class="actions">
                <button type="button" class="link-button" @click="download(dataset)">Download</button>
                <button v-if="canManage" type="button" class="link-button danger" @click="remove(dataset)">Delete</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<style scoped>
.external-data-panel { display: grid; gap: 18px; }
.heading { display: flex; justify-content: space-between; gap: 18px; align-items: flex-start; }
h2, p { margin-top: 0; }
h2 { margin-bottom: 5px; font-size: 20px; color: #111827; }
.heading p, .version-note { margin-bottom: 0; color: #6b7280; font-size: 14px; }
.heading code, .guidance code { color: #1f2937; }
.summary { white-space: nowrap; border: 1px solid #e5e7eb; border-radius: 8px; padding: 8px 11px; color: #4b5563; font-size: 13px; }
.summary strong { color: #111827; }
.guidance { border-left: 3px solid #f59e0b; background: #fffbeb; color: #78350f; border-radius: 5px; padding: 12px 14px; font-size: 14px; }
.version-row { display: grid; grid-template-columns: minmax(220px, 340px) 1fr; gap: 18px; align-items: end; }
.version-row label, .field { display: grid; gap: 6px; font-size: 13px; font-weight: 700; color: #374151; }
.field span { font-weight: 400; color: #6b7280; }
select, input[type="file"], input[type="text"] { width: 100%; box-sizing: border-box; border: 1px solid #d1d5db; border-radius: 7px; padding: 9px 10px; background: white; font: inherit; }
.upload-card { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; align-items: end; border: 1px solid #bfdbfe; background: #eff6ff; border-radius: 10px; padding: 16px; }
.description-field { grid-column: span 2; }
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
table { width: 100%; border-collapse: collapse; min-width: 920px; }
th, td { border-bottom: 1px solid #e5e7eb; padding: 10px; text-align: left; vertical-align: top; font-size: 13px; }
th { color: #6b7280; background: #f9fafb; }
td small { display: block; color: #6b7280; margin-top: 2px; }
.status { display: inline-flex; border-radius: 999px; padding: 3px 8px; font-size: 12px; font-weight: 700; }
.status.active { background: #ecfdf5; color: #047857; }
.status.inactive { background: #f3f4f6; color: #6b7280; }
.actions { white-space: nowrap; }
.link-button { border: 0; background: transparent; padding: 0; margin-right: 12px; color: #2563eb; font: inherit; font-weight: 600; cursor: pointer; }
.link-button.danger { color: #be123c; }
@media (max-width: 900px) {
  .heading, .version-row { display: flex; flex-direction: column; align-items: stretch; }
  .summary { align-self: flex-start; }
  .upload-card { grid-template-columns: 1fr 1fr; }
  .description-field { grid-column: auto; }
}
@media (max-width: 620px) {
  .upload-card { grid-template-columns: 1fr; }
}
</style>
