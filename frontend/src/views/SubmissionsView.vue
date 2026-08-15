<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useSubmissionStore, type SubmissionFilters, type SubmissionListItem } from '@/stores/submission'
import { useProjectStore } from '@/stores/project'
import SubmissionCard from '@/components/SubmissionCard.vue'

const router = useRouter()
const route = useRoute()
const submissionStore = useSubmissionStore()
const projectStore = useProjectStore()

const filters = ref<SubmissionFilters>({
  page: 0,
  size: 20,
  sort: 'submittedAt,desc'
})

const activeTab = ref('all')
const showFilters = ref(false)
const selectedSubmission = ref<SubmissionListItem | null>(null)

const statusOptions = [
  { value: '', label: 'All Statuses' },
  { value: 'SUBMITTED', label: 'Submitted' },
  { value: 'REVIEWED', label: 'Reviewed' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'FLAGGED', label: 'Flagged' }
]

const sourceOptions = [
  { value: '', label: 'All Sources' },
  { value: 'ODK Collect', label: 'ODK Collect' },
  { value: 'KoboCollect', label: 'KoboCollect' },
  { value: 'Web', label: 'Web Form' }
]

async function loadSubmissions() {
  try {
    await submissionStore.fetchSubmissions(filters.value)
  } catch (error) {
    console.error('Failed to load submissions:', error)
  }
}

async function loadProjects() {
  try {
    await projectStore.fetchProjects()
  } catch (error) {
    console.error('Failed to load projects:', error)
  }
}

function handlePageChange(page: number) {
  filters.value.page = page
  loadSubmissions()
}

function handleSortChange(sort: string) {
  filters.value.sort = sort
  filters.value.page = 0
  loadSubmissions()
}

function applyFilters() {
  filters.value.page = 0
  loadSubmissions()
}

function clearFilters() {
  filters.value = {
    page: 0,
    size: 20,
    sort: 'submittedAt,desc'
  }
  loadSubmissions()
}

function handleRowClick(submission: SubmissionListItem) {
  router.push(`/submissions/${submission.id}`)
}

async function handleDownloadXml(submission: SubmissionListItem) {
  try {
    await submissionStore.downloadXml(submission.id)
  } catch (error) {
    console.error('Failed to download XML:', error)
  }
}

async function handleDownloadJson(submission: SubmissionListItem) {
  try {
    await submissionStore.downloadJson(submission.id)
  } catch (error) {
    console.error('Failed to download JSON:', error)
  }
}

function formatDate(dateString: string | null): string {
  if (!dateString) return '-'
  return new Date(dateString).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function getStatusClass(status: string): string {
  switch (status?.toUpperCase()) {
    case 'SUBMITTED':
      return 'status-submitted'
    case 'REVIEWED':
      return 'status-reviewed'
    case 'APPROVED':
      return 'status-approved'
    case 'REJECTED':
      return 'status-rejected'
    case 'FLAGGED':
      return 'status-flagged'
    default:
      return ''
  }
}

function getStatusLabel(status: string): string {
  return status || 'Unknown'
}

function getSourceLabel(source: string | null): string {
  return source || 'Unknown'
}

onMounted(async () => {
  await loadProjects()
  await loadSubmissions()
})

watch(() => route.query, () => {
  // Reload when query params change
  loadSubmissions()
}, { deep: true })
</script>

<template>
  <div class="submissions-view">
    <div class="view-header">
      <div class="header-content">
        <h1>Submissions</h1>
        <p class="subtitle">Review and manage form submissions</p>
      </div>
      <div class="header-actions">
        <button class="btn btn-secondary" @click="showFilters = !showFilters">
          <svg class="icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 4a1 1 0 011-1h16a1 1 0 011 1v2.586a1 1 0 01-.293.707l-6.414 6.414a1 1 0 00-.293.707V17l-4 4v-6.586a1 1 0 00-.293-.707L3.293 7.293A1 1 0 013 6.586V4z"></path>
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 18a1 1 0 001-1v-2.586a1 1 0 01.293-.707l6.414-6.414a1 1 0 00.293-.707V5l4-4v6.586a1 1 0 01-.293.707L20.707 16.707A1 1 0 0021 17v2a1 1 0 01-1 1h-2"></path>
          </svg>
          Filters
        </button>
        <span class="results-count">{{ submissionStore.pagination.totalElements }} submissions</span>
      </div>
    </div>

    <!-- Filters Panel -->
    <div v-if="showFilters" class="filters-panel">
      <div class="filters-grid">
        <div class="filter-group">
          <label>Project</label>
          <select v-model="filters.projectId" class="form-select">
            <option value="">All Projects</option>
            <option v-for="project in projectStore.projects" :key="project.id" :value="project.id">
              {{ project.name }}
            </option>
          </select>
        </div>

        <div class="filter-group">
          <label>Form</label>
          <select v-model="filters.formId" class="form-select">
            <option value="">All Forms</option>
            <!-- Forms would be loaded based on selected project -->
          </select>
        </div>

        <div class="filter-group">
          <label>Status</label>
          <select v-model="filters.status" class="form-select">
            <option v-for="opt in statusOptions" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </option>
          </select>
        </div>

        <div class="filter-group">
          <label>Source</label>
          <select v-model="filters.source" class="form-select">
            <option v-for="opt in sourceOptions" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </option>
          </select>
        </div>

        <div class="filter-group">
          <label>Date From</label>
          <input type="date" v-model="filters.dateFrom" class="form-input" />
        </div>

        <div class="filter-group">
          <label>Date To</label>
          <input type="date" v-model="filters.dateTo" class="form-input" />
        </div>

        <div class="filter-group">
          <label>Submitter</label>
          <input type="text" v-model="filters.submittedById" class="form-input" placeholder="User ID" />
        </div>
      </div>
      <div class="filters-actions">
        <button class="btn btn-primary" @click="applyFilters">Apply Filters</button>
        <button class="btn btn-secondary" @click="clearFilters">Clear</button>
      </div>
    </div>

    <!-- Submissions Table -->
    <div class="submissions-table-container">
      <table class="submissions-table">
        <thead>
          <tr>
            <th @click="handleSortChange('formTitle,asc')" class="sortable">
              Form
              <svg class="sort-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 15l7-7 7 7"></path>
              </svg>
            </th>
            <th @click="handleSortChange('instanceId,asc')" class="sortable">
              Instance ID
              <svg class="sort-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 15l7-7 7 7"></path>
              </svg>
            </th>
            <th @click="handleSortChange('status,asc')" class="sortable">
              Status
              <svg class="sort-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 15l7-7 7 7"></path>
              </svg>
            </th>
            <th @click="handleSortChange('source,asc')" class="sortable">
              Source
              <svg class="sort-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 15l7-7 7 7"></path>
              </svg>
            </th>
            <th @click="handleSortChange('submittedAt,desc')" class="sortable">
              Submitted At
              <svg class="sort-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 15l7-7 7 7"></path>
              </svg>
            </th>
            <th @click="handleSortChange('submittedByName,asc')" class="sortable">
              Submitted By
              <svg class="sort-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 15l7-7 7 7"></path>
              </svg>
            </th>
            <th>Attachments</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="submissionStore.loading" class="loading-row">
            <td colspan="8" class="loading-cell">
              <div class="spinner"></div>
              Loading submissions...
            </td>
          </tr>
          <tr v-else-if="submissionStore.submissions.length === 0" class="empty-row">
            <td colspan="8" class="empty-cell">
              <div class="empty-state">
                <svg fill="none" stroke="currentColor" viewBox="0 0 24 24" class="empty-icon">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"></path>
                </svg>
                <p>No submissions found</p>
                <p class="empty-hint">Try adjusting your filters or check back later</p>
              </div>
            </td>
          </tr>
          <SubmissionCard
            v-else
            v-for="submission in submissionStore.submissions"
            :key="submission.id"
            :submission="submission"
            @click="handleRowClick"
            @download-xml="handleDownloadXml"
            @download-json="handleDownloadJson"
          />
        </tbody>
      </table>
    </div>

    <!-- Pagination -->
    <div v-if="submissionStore.pagination.totalPages > 1" class="pagination">
      <button
        class="btn btn-secondary btn-sm"
        :disabled="submissionStore.pagination.number === 0"
        @click="handlePageChange(submissionStore.pagination.number - 1)"
      >
        Previous
      </button>
      <span class="pagination-info">
        Page {{ submissionStore.pagination.number + 1 }} of {{ submissionStore.pagination.totalPages }}
      </span>
      <button
        class="btn btn-secondary btn-sm"
        :disabled="submissionStore.pagination.number >= submissionStore.pagination.totalPages - 1"
        @click="handlePageChange(submissionStore.pagination.number + 1)"
      >
        Next
      </button>
    </div>
  </div>
</template>

<style scoped>
.submissions-view {
  padding: 24px;
  max-width: 1400px;
  margin: 0 auto;
}

.view-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
  gap: 16px;
}

.header-content h1 {
  margin: 0 0 4px 0;
  font-size: 28px;
  font-weight: 700;
  color: #1a1a1a;
}

.subtitle {
  margin: 0;
  color: #6b7280;
  font-size: 16px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 16px;
}

.results-count {
  color: #6b7280;
  font-size: 14px;
  font-weight: 500;
}

/* Filters Panel */
.filters-panel {
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 24px;
}

.filters-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.filter-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.filter-group label {
  font-size: 13px;
  font-weight: 600;
  color: #374151;
}

.form-select,
.form-input {
  padding: 8px 12px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 14px;
  background: white;
  transition: border-color 0.2s, box-shadow 0.2s;
}

.form-select:focus,
.form-input:focus {
  outline: none;
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

.filters-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}

/* Table */
.submissions-table-container {
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  overflow: hidden;
  background: white;
}

.submissions-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.submissions-table th {
  background: #f9fafb;
  padding: 12px 16px;
  text-align: left;
  font-weight: 600;
  color: #374151;
  border-bottom: 1px solid #e5e7eb;
  white-space: nowrap;
  user-select: none;
}

.submissions-table th.sortable {
  cursor: pointer;
}

.submissions-table th.sortable:hover {
  background: #f3f4f6;
}

.sort-icon {
  width: 14px;
  height: 14px;
  margin-left: 4px;
  color: #9ca3af;
  display: inline-block;
  vertical-align: middle;
}

.submissions-table td {
  padding: 12px 16px;
  border-bottom: 1px solid #f3f4f6;
  color: #1a1a1a;
  vertical-align: middle;
}

.submissions-table tbody tr:last-child td {
  border-bottom: none;
}

.submissions-table tbody tr {
  cursor: pointer;
  transition: background 0.15s;
}

.submissions-table tbody tr:hover {
  background: #f9fafb;
}

.loading-row,
.empty-row {
  text-align: center;
}

.loading-cell,
.empty-cell {
  padding: 48px 24px !important;
}

.spinner {
  width: 24px;
  height: 24px;
  border: 2px solid #e5e7eb;
  border-top-color: #3b82f6;
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin: 0 auto 12px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  color: #6b7280;
}

.empty-icon {
  width: 48px;
  height: 48px;
  color: #d1d5db;
}

.empty-state p {
  margin: 0;
  font-size: 16px;
}

.empty-hint {
  font-size: 14px !important;
  color: #9ca3af !important;
}

/* Status Badges */
.status-badge {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 9999px;
  font-size: 12px;
  font-weight: 500;
}

.status-submitted { background: #dcfce7; color: #166534; }
.status-reviewed { background: #dbeafe; color: #1e40af; }
.status-approved { background: #dcfce7; color: #166534; }
.status-rejected { background: #fef2f2; color: #991b1b; }
.status-flagged { background: #fef3c7; color: #92400e; }

/* Source Badge */
.source-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
  background: #f3f4f6;
  color: #374151;
}

/* Instance ID */
.instance-id {
  font-family: monospace;
  font-size: 12px;
  color: #6b7280;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Attachments */
.attachment-count {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: #6b7280;
  font-size: 13px;
}

.attachment-count svg {
  width: 16px;
  height: 16px;
}

/* Actions */
.actions {
  display: flex;
  gap: 8px;
  white-space: nowrap;
}

.btn-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  background: white;
  color: #374151;
  cursor: pointer;
  transition: all 0.15s;
}

.btn-icon:hover {
  background: #f3f4f6;
  border-color: #9ca3af;
}

.btn-icon:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn-icon svg {
  width: 16px;
  height: 16px;
}

/* Pagination */
.pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 24px;
  padding: 16px;
}

.pagination-info {
  color: #6b7280;
  font-size: 14px;
  font-weight: 500;
}

/* Buttons */
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 8px 16px;
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
  width: 16px;
  height: 16px;
}
</style>