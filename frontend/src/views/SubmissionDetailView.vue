<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import {
  useSubmissionStore,
  type AttachmentInfo,
  type SubmissionDetail,
  type UpdateStatusRequest,
} from '@/stores/submission'

type ReviewTab = 'answers' | 'xml' | 'json' | 'attachments' | 'events' | 'metadata'

const router = useRouter()
const route = useRoute()
const submissionStore = useSubmissionStore()
const authStore = useAuthStore()

const submission = ref<SubmissionDetail | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)
const activeTab = ref<ReviewTab>('answers')
const statusUpdateLoading = ref(false)
const showStatusModal = ref(false)
const pendingStatus = ref('')
const pendingNotes = ref('')

const statusOptions = [
  { value: 'SUBMITTED', label: 'Submitted' },
  { value: 'REVIEWED', label: 'Reviewed' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'FLAGGED', label: 'Flagged' },
]

const canReview = computed(() =>
  ['SYSTEM_ADMIN', 'ADMIN', 'PROJECT_ADMIN', 'PROJECT_MANAGER']
    .some(role => authStore.hasRole(role)),
)

const sourceClient = computed(() => {
  const metadata = submission.value?.metadata
  if (!metadata) return 'Unknown'
  const value = metadata.sourceClient ?? metadata.source
  return value == null || String(value).trim() === '' ? 'Unknown' : String(value)
})

const formattedJson = computed(() => {
  const raw = submission.value?.jsonContent
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})

async function loadSubmission() {
  const id = route.params.id as string
  if (!id) return

  loading.value = true
  error.value = null
  try {
    submission.value = await submissionStore.fetchSubmissionDetail(id)
  } catch (err: any) {
    error.value = err.response?.data?.message || 'Failed to load submission'
  } finally {
    loading.value = false
  }
}

async function handleDownloadXml() {
  const id = route.params.id as string
  if (!id) return
  await submissionStore.downloadXml(id)
}

async function handleDownloadJson() {
  const id = route.params.id as string
  if (!id) return
  await submissionStore.downloadJson(id)
}

async function handleDownloadAttachment(attachment: AttachmentInfo) {
  const id = route.params.id as string
  if (!id) return
  await submissionStore.downloadAttachment(id, attachment.id, attachment.fileName)
}

function openStatusModal(newStatus = submission.value?.status || 'REVIEWED') {
  if (!canReview.value) return
  pendingStatus.value = newStatus
  pendingNotes.value = submission.value?.reviewNotes || ''
  showStatusModal.value = true
}

async function confirmStatusUpdate() {
  const id = route.params.id as string
  if (!id || !pendingStatus.value || !canReview.value) return

  statusUpdateLoading.value = true
  try {
    const request: UpdateStatusRequest = {
      status: pendingStatus.value,
      reviewNotes: pendingNotes.value.trim() || undefined,
    }
    submission.value = await submissionStore.updateStatus(id, request)
    showStatusModal.value = false
  } finally {
    statusUpdateLoading.value = false
  }
}

function formatDate(value: string | null): string {
  if (!value) return '—'
  return new Date(value).toLocaleString()
}

function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return bytes === 0 ? '0 Bytes' : '—'
  const units = ['Bytes', 'KB', 'MB', 'GB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${Number((bytes / Math.pow(1024, index)).toFixed(2))} ${units[index]}`
}

function getStatusClass(status: string): string {
  return `status-${(status || '').toLowerCase()}`
}

function answerLabel(questionName: string, questionPath: string): string {
  return questionName || questionPath || 'Answer'
}

onMounted(loadSubmission)
watch(() => route.params.id, loadSubmission)
</script>

<template>
  <div class="submission-detail-view">
    <div v-if="loading" class="loading-overlay">
      <div class="spinner"></div>
      <p>Loading submission...</p>
    </div>

    <div v-else-if="error" class="error-state">
      <h2>Failed to load submission</h2>
      <p>{{ error }}</p>
      <div>
        <button class="btn btn-primary" @click="loadSubmission">Retry</button>
        <button class="btn btn-secondary" @click="router.push('/submissions')">Back to List</button>
      </div>
    </div>

    <div v-else-if="submission" class="detail-content">
      <header class="detail-header">
        <div class="header-left">
          <button class="btn btn-secondary" @click="router.push('/submissions')">Back</button>
          <div class="title-section">
            <h1>{{ submission.formTitle }}</h1>
            <div class="meta-info">
              <span class="instance-id">{{ submission.instanceId }}</span>
              <span class="divider">•</span>
              <span>{{ sourceClient }}</span>
              <template v-if="submission.formVersion">
                <span class="divider">•</span>
                <span class="form-version">v{{ submission.formVersion }}</span>
              </template>
            </div>
          </div>
        </div>

        <div class="header-right">
          <span class="status-badge-large" :class="getStatusClass(submission.status)">
            {{ submission.status }}
          </span>
          <div class="header-actions">
            <button class="btn btn-secondary" aria-label="XML" @click="handleDownloadXml">XML</button>
            <button class="btn btn-secondary" aria-label="JSON" @click="handleDownloadJson">JSON</button>
            <button
              v-if="canReview"
              class="btn btn-primary"
              data-testid="update-status"
              @click="openStatusModal()"
            >
              Update Status
            </button>
          </div>
        </div>
      </header>

      <section class="review-summary">
        <div>
          <span class="summary-label">Submitted</span>
          <strong>{{ formatDate(submission.submittedAt) }}</strong>
        </div>
        <div>
          <span class="summary-label">Submitted by</span>
          <strong>{{ submission.submittedByName || submission.submittedById || 'Unknown' }}</strong>
        </div>
        <div>
          <span class="summary-label">Reviewed</span>
          <strong>{{ formatDate(submission.reviewedAt) }}</strong>
        </div>
        <div>
          <span class="summary-label">Review notes</span>
          <strong>{{ submission.reviewNotes || '—' }}</strong>
        </div>
      </section>

      <nav class="tabs" aria-label="Submission review sections">
        <button class="tab" :class="{ active: activeTab === 'answers' }" @click="activeTab = 'answers'">
          Answers ({{ submission.answers.length }})
        </button>
        <button class="tab" :class="{ active: activeTab === 'xml' }" @click="activeTab = 'xml'">
          Raw XML
        </button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">
          JSON
        </button>
        <button class="tab" :class="{ active: activeTab === 'attachments' }" @click="activeTab = 'attachments'">
          Attachments ({{ submission.attachments.length }})
        </button>
        <button class="tab" :class="{ active: activeTab === 'events' }" @click="activeTab = 'events'">
          Audit / Events ({{ submission.events.length }})
        </button>
        <button class="tab" :class="{ active: activeTab === 'metadata' }" @click="activeTab = 'metadata'">
          Metadata
        </button>
      </nav>

      <section class="tab-panels">
        <div v-if="activeTab === 'answers'" class="tab-panel" data-testid="answers-panel">
          <div v-if="submission.answers.length" class="answers-table-wrap">
            <table class="answers-table">
              <thead>
                <tr><th>Question</th><th>Path</th><th>Repeat</th><th>Value</th></tr>
              </thead>
              <tbody>
                <tr v-for="answer in submission.answers" :key="answer.id">
                  <td><strong>{{ answerLabel(answer.questionName, answer.questionPath) }}</strong></td>
                  <td><code>{{ answer.questionPath }}</code></td>
                  <td>{{ answer.repeatIndex === null ? '—' : answer.repeatIndex }}</td>
                  <td>
                    <span v-if="answer.value !== null && answer.value !== ''">{{ answer.value }}</span>
                    <span v-else class="empty-value">(empty)</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-else class="empty-state"><p>No normalized answers available.</p></div>
        </div>

        <div v-else-if="activeTab === 'xml'" class="tab-panel" data-testid="xml-panel">
          <div class="code-panel-heading">
            <div><h2>Raw submission XML</h2><p>Original XML preserved at intake.</p></div>
            <button class="btn btn-secondary btn-sm" @click="handleDownloadXml">Download XML</button>
          </div>
          <pre class="code-view">{{ submission.xmlContent || 'No XML content available.' }}</pre>
        </div>

        <div v-else-if="activeTab === 'json'" class="tab-panel" data-testid="json-panel">
          <div class="code-panel-heading">
            <div><h2>Parsed submission JSON</h2><p>Nested JSON generated from the stored XML.</p></div>
            <button class="btn btn-secondary btn-sm" @click="handleDownloadJson">Download JSON</button>
          </div>
          <pre class="code-view">{{ formattedJson || 'No JSON content available.' }}</pre>
        </div>

        <div v-else-if="activeTab === 'attachments'" class="tab-panel" data-testid="attachments-panel">
          <div v-if="submission.attachments.length" class="attachments-grid">
            <article v-for="attachment in submission.attachments" :key="attachment.id" class="attachment-card">
              <div class="attachment-info">
                <h4 class="attachment-name">{{ attachment.fileName }}</h4>
                <div class="attachment-meta">
                  <span>{{ attachment.contentType || 'application/octet-stream' }}</span>
                  <span class="divider">•</span>
                  <span>{{ formatFileSize(attachment.fileSize) }}</span>
                  <template v-if="attachment.answerName">
                    <span class="divider">•</span>
                    <span class="attachment-answer">Answer: {{ attachment.answerName }}</span>
                  </template>
                </div>
              </div>
              <button
                class="btn btn-secondary btn-sm"
                :aria-label="`Download attachment ${attachment.fileName}`"
                @click="handleDownloadAttachment(attachment)"
              >
                Download
              </button>
            </article>
          </div>
          <div v-else class="empty-state"><p>No attachments.</p></div>
        </div>

        <div v-else-if="activeTab === 'events'" class="tab-panel" data-testid="events-panel">
          <div v-if="submission.events.length" class="events-timeline">
            <article v-for="event in submission.events" :key="event.id" class="event-item">
              <div class="event-marker"></div>
              <div class="event-content">
                <div class="event-header">
                  <strong class="event-type">{{ event.eventType }}</strong>
                  <time class="event-time">{{ formatDate(event.createdAt) }}</time>
                </div>
                <div v-if="event.createdById" class="event-user">Actor: {{ event.createdById }}</div>
                <pre v-if="event.eventData && Object.keys(event.eventData).length" class="event-json">{{ JSON.stringify(event.eventData, null, 2) }}</pre>
              </div>
            </article>
          </div>
          <div v-else class="empty-state"><p>No submission events recorded.</p></div>
        </div>

        <div v-else class="tab-panel" data-testid="metadata-panel">
          <div class="metadata-grid">
            <section class="metadata-section">
              <h3>Submission information</h3>
              <dl class="metadata-list">
                <div><dt>ID</dt><dd>{{ submission.id }}</dd></div>
                <div><dt>Instance ID</dt><dd>{{ submission.instanceId }}</dd></div>
                <div><dt>Form ID</dt><dd>{{ submission.formId }}</dd></div>
                <div><dt>Form XMLNS</dt><dd>{{ submission.formXmlns }}</dd></div>
                <div><dt>Version</dt><dd>{{ submission.formVersion || '—' }}</dd></div>
                <div><dt>Source client</dt><dd>{{ sourceClient }}</dd></div>
                <div><dt>Status</dt><dd>{{ submission.status }}</dd></div>
              </dl>
            </section>
            <section class="metadata-section">
              <h3>Additional metadata</h3>
              <pre class="code-view compact">{{ JSON.stringify(submission.metadata || {}, null, 2) }}</pre>
            </section>
          </div>
        </div>
      </section>
    </div>
  </div>

  <div v-if="showStatusModal" class="modal-overlay" @click.self="showStatusModal = false">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="review-title">
      <div class="modal-header">
        <h2 id="review-title">Update Submission Status</h2>
        <button class="modal-close" aria-label="Close review dialog" @click="showStatusModal = false">×</button>
      </div>
      <div class="modal-body">
        <div class="form-group">
          <label for="review-status">New Status</label>
          <select id="review-status" v-model="pendingStatus" class="form-select">
            <option v-for="option in statusOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
        </div>
        <div class="form-group">
          <label for="review-notes">Review Notes</label>
          <textarea id="review-notes" v-model="pendingNotes" class="form-textarea" rows="4"></textarea>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-secondary" @click="showStatusModal = false">Cancel</button>
        <button
          class="btn btn-primary"
          data-testid="confirm-status"
          :disabled="statusUpdateLoading"
          @click="confirmStatusUpdate"
        >
          {{ statusUpdateLoading ? 'Updating...' : 'Update Status' }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.submission-detail-view {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
}

/* Loading & Error */
.loading-overlay,
.error-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  gap: 16px;
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

.error-state {
  text-align: center;
  color: #6b7280;
}

.error-icon {
  width: 64px;
  height: 64px;
  color: #ef4444;
}

.error-state h2 {
  margin: 0;
  color: #1a1a1a;
}

.error-state p {
  margin: 8px 0 24px;
}

.error-state .btn {
  margin: 0 8px;
}

/* Header */
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
  gap: 24px;
  flex-wrap: wrap;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.title-section h1 {
  margin: 0 0 8px 0;
  font-size: 28px;
  font-weight: 700;
  color: #1a1a1a;
}

.meta-info {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #6b7280;
}

.instance-id {
  font-family: monospace;
  font-size: 13px;
}

.divider {
  color: #d1d5db;
}

.form-id {
  font-family: monospace;
  font-size: 13px;
}

.form-version {
  background: #f3f4f6;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.status-badge-large {
  display: inline-flex;
  align-items: center;
  padding: 8px 16px;
  border-radius: 9999px;
  font-size: 14px;
  font-weight: 600;
}

.status-submitted { background: #dcfce7; color: #166534; }
.status-reviewed { background: #dbeafe; color: #1e40af; }
.status-approved { background: #dcfce7; color: #166534; }
.status-rejected { background: #fef2f2; color: #991b1b; }
.status-flagged { background: #fef3c7; color: #92400e; }

.header-actions {
  display: flex;
  gap: 8px;
}

.dropdown {
  position: relative;
}

/* Tabs */
.tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid #e5e7eb;
  margin-bottom: 24px;
  overflow-x: auto;
}

.tab {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 20px;
  border: none;
  background: transparent;
  color: #6b7280;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  transition: all 0.15s;
  white-space: nowrap;
}

.tab:hover {
  color: #374151;
  background: #f9fafb;
}

.tab.active {
  color: #3b82f6;
  border-bottom-color: #3b82f6;
}

.tab .icon {
  width: 18px;
  height: 18px;
}

/* Tab Panels */
.tab-panel {
  animation: fadeIn 0.2s ease;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}

/* Form Data */
.data-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.data-item {
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.data-label {
  font-size: 12px;
  font-weight: 600;
  color: #6b7280;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.data-value {
  font-size: 14px;
  color: #1a1a1a;
  word-break: break-word;
}

.empty-value {
  color: #9ca3af;
  font-style: italic;
}

.repeat-index {
  font-size: 11px;
  color: #3b82f6;
  font-weight: 500;
  background: #dbeafe;
  padding: 2px 6px;
  border-radius: 4px;
  align-self: flex-start;
}

/* Attachments */
.attachments-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.attachment-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  transition: all 0.15s;
}

.attachment-card:hover {
  border-color: #d1d5db;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
}

.attachment-preview {
  width: 60px;
  height: 60px;
  border-radius: 8px;
  overflow: hidden;
  flex-shrink: 0;
  background: white;
  border: 1px solid #e5e7eb;
}

.attachment-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.file-icon {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #6b7280;
}

.file-icon svg {
  width: 28px;
  height: 28px;
}

.attachment-info {
  flex: 1;
  min-width: 0;
}

.attachment-name {
  margin: 0 0 4px 0;
  font-size: 14px;
  font-weight: 500;
  color: #1a1a1a;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.attachment-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #6b7280;
}

.attachment-type {
  font-family: monospace;
}

.attachment-size {
  font-weight: 500;
}

.attachment-answer {
  color: #3b82f6;
  font-weight: 500;
}

/* Events */
.events-timeline {
  position: relative;
  padding-left: 24px;
}

.events-timeline::before {
  content: '';
  position: absolute;
  left: 7px;
  top: 0;
  bottom: 0;
  width: 2px;
  background: #e5e7eb;
}

.event-item {
  position: relative;
  padding-bottom: 24px;
  display: flex;
  gap: 16px;
}

.event-item:last-child {
  padding-bottom: 0;
}

.event-marker {
  position: absolute;
  left: -24px;
  top: 0;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #3b82f6;
  border: 3px solid white;
  box-shadow: 0 0 0 2px #3b82f6;
  flex-shrink: 0;
}

.event-content {
  flex: 1;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 16px;
}

.event-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.event-type {
  font-weight: 600;
  color: #1a1a1a;
  text-transform: capitalize;
}

.event-time {
  font-size: 12px;
  color: #6b7280;
}

.event-data pre {
  margin: 0;
  padding: 12px;
  background: #1a1a1a;
  color: #e5e7eb;
  border-radius: 6px;
  font-size: 12px;
  overflow-x: auto;
  max-height: 300px;
}

.event-user {
  margin-top: 8px;
  font-size: 12px;
  color: #6b7280;
}

/* Metadata */
.metadata-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
  gap: 24px;
}

.metadata-section {
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  padding: 20px;
}

.metadata-section h3 {
  margin: 0 0 16px 0;
  font-size: 16px;
  font-weight: 600;
  color: #1a1a1a;
  padding-bottom: 12px;
  border-bottom: 1px solid #e5e7eb;
}

.metadata-list {
  display: grid;
  grid-template-columns: 160px 1fr;
  gap: 8px 16px;
  margin: 0;
}

.metadata-list dt {
  font-size: 13px;
  font-weight: 500;
  color: #6b7280;
}

.metadata-list dd {
  margin: 0;
  font-size: 13px;
  color: #1a1a1a;
  word-break: break-word;
}

.metadata-list pre {
  margin: 0;
  padding: 8px;
  background: #1a1a1a;
  color: #e5e7eb;
  border-radius: 6px;
  font-size: 11px;
  overflow-x: auto;
  max-height: 200px;
}

/* Empty State */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 24px;
  text-align: center;
  color: #6b7280;
}

.empty-icon {
  width: 64px;
  height: 64px;
  color: #d1d5db;
  margin-bottom: 16px;
}

.empty-state p {
  margin: 0;
  font-size: 16px;
}

/* Modal */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  z-index: 1000;
  animation: fadeIn 0.2s ease;
}

.modal {
  background: white;
  border-radius: 16px;
  width: 100%;
  max-width: 500px;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
  animation: slideUp 0.2s ease;
}

@keyframes slideUp {
  from { opacity: 0; transform: translateY(20px); }
  to { opacity: 1; transform: translateY(0); }
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid #e5e7eb;
}

.modal-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.modal-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  color: #6b7280;
  cursor: pointer;
  border-radius: 8px;
  transition: all 0.15s;
}

.modal-close:hover {
  background: #f3f4f6;
  color: #1a1a1a;
}

.modal-close svg {
  width: 20px;
  height: 20px;
}

.modal-body {
  padding: 24px;
}

.form-group {
  margin-bottom: 20px;
}

.form-group label {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 500;
  color: #374151;
}

.form-select,
.form-textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 14px;
  background: white;
  transition: border-color 0.2s, box-shadow 0.2s;
  font-family: inherit;
}

.form-select:focus,
.form-textarea:focus {
  outline: none;
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

.form-textarea {
  resize: vertical;
  min-height: 100px;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid #e5e7eb;
}

/* Buttons */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px 20px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  border: 1px solid transparent;
}

.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-sm {
  padding: 6px 12px;
  font-size: 13px;
}

.btn-primary {
  background: #3b82f6;
  color: white;
  border-color: #3b82f6;
}

.btn-primary:hover:not(:disabled) {
  background: #2563eb;
  border-color: #2563eb;
}

.btn-secondary {
  background: white;
  color: #374151;
  border-color: #d1d5db;
}

.btn-secondary:hover:not(:disabled) {
  background: #f9fafb;
  border-color: #9ca3af;
}

.btn-danger {
  background: #ef4444;
  color: white;
  border-color: #ef4444;
}

.btn-danger:hover:not(:disabled) {
  background: #dc2626;
  border-color: #dc2626;
}

.icon {
  width: 18px;
  height: 18px;
}

/* AUDIT-09 review fidelity */
.review-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin: 0 0 22px;
}
.review-summary > div {
  border: 1px solid #e5e7eb;
  background: #f9fafb;
  border-radius: 10px;
  padding: 14px;
  min-width: 0;
}
.summary-label {
  display: block;
  margin-bottom: 5px;
  color: #6b7280;
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: .04em;
}
.review-summary strong {
  display: block;
  color: #111827;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.answers-table-wrap {
  overflow-x: auto;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
}
.answers-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.answers-table th,
.answers-table td {
  padding: 12px 14px;
  border-bottom: 1px solid #e5e7eb;
  text-align: left;
  vertical-align: top;
}
.answers-table th {
  background: #f9fafb;
  color: #4b5563;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: .04em;
}
.answers-table tr:last-child td { border-bottom: 0; }
.answers-table code {
  white-space: nowrap;
  color: #4b5563;
}
.code-panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}
.code-panel-heading h2 { margin: 0 0 4px; font-size: 18px; }
.code-panel-heading p { margin: 0; color: #6b7280; font-size: 13px; }
.code-view {
  margin: 0;
  padding: 18px;
  min-height: 180px;
  max-height: 620px;
  overflow: auto;
  border-radius: 10px;
  background: #111827;
  color: #e5e7eb;
  font: 12px/1.6 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.code-view.compact { min-height: 120px; max-height: 360px; }
.event-json {
  margin: 10px 0 0;
  padding: 12px;
  overflow: auto;
  border-radius: 6px;
  background: #111827;
  color: #e5e7eb;
  font-size: 12px;
}
@media (max-width: 900px) {
  .review-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 640px) {
  .submission-detail-view { padding: 16px; }
  .review-summary { grid-template-columns: 1fr; }
  .header-left, .header-right { width: 100%; align-items: flex-start; }
  .header-actions { flex-wrap: wrap; }
  .metadata-grid { grid-template-columns: 1fr; }
  .metadata-list { grid-template-columns: 1fr; }
  .code-panel-heading { flex-direction: column; }
}

</style>