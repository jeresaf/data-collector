<script setup lang="ts">
import type { SubmissionListItem } from '@/stores/submission'

interface Props {
  submission: SubmissionListItem
}

interface Emits {
  (e: 'click', submission: SubmissionListItem): void
  (e: 'downloadXml', submission: SubmissionListItem): void
  (e: 'downloadJson', submission: SubmissionListItem): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

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

function handleClick(event: MouseEvent) {
  // Don't trigger row click if clicking on action buttons
  const target = event.target as HTMLElement
  if (target.closest('.btn-icon')) {
    return
  }
  emit('click', props.submission)
}
</script>

<template>
  <tr class="submission-row" @click="handleClick">
    <td class="form-name">{{ submission.formTitle }}</td>
    <td class="instance-id">{{ submission.instanceId }}</td>
    <td>
      <span class="status-badge" :class="getStatusClass(submission.status)">
        {{ getStatusLabel(submission.status) }}
      </span>
    </td>
    <td>
      <span class="source-badge">{{ getSourceLabel(submission.source) }}</span>
    </td>
    <td class="submitted-at">{{ formatDate(submission.submittedAt) }}</td>
    <td class="submitted-by">
      <span v-if="submission.submittedByName">{{ submission.submittedByName }}</span>
      <span v-else class="text-muted">{{ submission.submittedById || 'Unknown' }}</span>
    </td>
    <td class="attachments">
      <span class="attachment-count">
        <svg fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13"></path>
        </svg>
        {{ submission.attachmentCount }}
      </span>
    </td>
    <td class="actions">
      <button class="btn-icon" title="View Details" aria-label="View submission details" @click.stop="$emit('click', submission)">
        <svg fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"></path>
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"></path>
        </svg>
      </button>
      <button class="btn-icon" title="Download XML" aria-label="Download submission XML" @click.stop="$emit('downloadXml', submission)">
        <svg fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"></path>
        </svg>
      </button>
      <button class="btn-icon" title="Download JSON" aria-label="Download submission JSON" @click.stop="$emit('downloadJson', submission)">
        <svg fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"></path>
        </svg>
      </button>
    </td>
  </tr>
</template>

<style scoped>
.submission-row:hover {
  background: #f9fafb;
}

.form-name {
  font-weight: 500;
  color: #1a1a1a;
  max-width: 250px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.instance-id {
  font-family: monospace;
  font-size: 12px;
  color: #6b7280;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

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

.submitted-at {
  color: #6b7280;
  font-size: 13px;
  white-space: nowrap;
}

.submitted-by {
  color: #374151;
  font-size: 13px;
}

.text-muted {
  color: #9ca3af;
}

.attachments {
  text-align: center;
}

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

.actions {
  display: flex;
  gap: 4px;
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
</style>