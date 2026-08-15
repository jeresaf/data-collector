<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useSubmissionStore, type SubmissionListItem } from '@/stores/submission'
import SubmissionCard from '@/components/SubmissionCard.vue'

interface Props {
  projectId: string
}

const props = defineProps<Props>()
const router = useRouter()
const submissionStore = useSubmissionStore()

const submissions = ref<SubmissionListItem[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

async function loadSubmissions() {
  loading.value = true
  error.value = null
  try {
    const response = await submissionStore.fetchSubmissions({
      projectId: props.projectId,
      page: 0,
      size: 20,
      sort: 'submittedAt,desc'
    })
    submissions.value = response.content
  } catch (err) {
    console.error('Failed to load submissions:', err)
    error.value = 'Failed to load submissions'
  } finally {
    loading.value = false
  }
}

function handleRowClick(submission: SubmissionListItem) {
  router.push(`/submissions/${submission.id}`)
}

async function handleDownloadXml(submission: SubmissionListItem) {
  try {
    await submissionStore.downloadXml(submission.id)
  } catch (err) {
    console.error('Failed to download XML:', err)
  }
}

async function handleDownloadJson(submission: SubmissionListItem) {
  try {
    await submissionStore.downloadJson(submission.id)
  } catch (err) {
    console.error('Failed to download JSON:', err)
  }
}

onMounted(loadSubmissions)
watch(() => props.projectId, loadSubmissions)
</script>

<template>
  <div class="submission-list">
    <div class="list-header">
      <h2>Submissions</h2>
    </div>

    <div v-if="loading" class="loading">Loading submissions...</div>
    <div v-else-if="error" class="error-state" role="alert">{{ error }}</div>

    <div v-else-if="submissions.length === 0" class="empty-state">
      <p>No submissions found.</p>
    </div>

    <div v-else class="submissions-table-container">
      <table class="submissions-table">
        <thead>
          <tr>
            <th>Form</th>
            <th>Instance ID</th>
            <th>Status</th>
            <th>Source</th>
            <th>Submitted At</th>
            <th>Submitted By</th>
            <th>Attachments</th>
            <th>Actions</th>
          </tr>
        </thead>
        <tbody>
          <SubmissionCard
            v-for="submission in submissions"
            :key="submission.id"
            :submission="submission"
            @click="handleRowClick"
            @download-xml="handleDownloadXml"
            @download-json="handleDownloadJson"
          />
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.submission-list {
  padding: 0;
}

.list-header {
  margin-bottom: 24px;
}

.list-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #1a1a1a;
}

.loading,
.error-state {
  text-align: center;
  padding: 48px;
  color: #6b7280;
  font-size: 16px;
}

.error-state {
  color: #b91c1c;
}

.empty-state {
  text-align: center;
  padding: 64px 24px;
  background: #f9fafb;
  border-radius: 12px;
  border: 1px dashed #d1d5db;
}

.empty-state p {
  margin: 0;
  color: #6b7280;
  font-size: 16px;
}

.submissions-table-container {
  overflow-x: auto;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
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
}

.submissions-table :deep(td) {
  padding: 12px 16px;
  border-bottom: 1px solid #f3f4f6;
}
</style>
