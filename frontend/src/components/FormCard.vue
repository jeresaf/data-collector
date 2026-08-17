<script setup lang="ts">
import { Form } from '@/stores/form'
import { useRouter } from 'vue-router'

interface Props {
  form: Form
}

interface Emits {
  (e: 'manage', formId: string): void
  (e: 'edit', form: Form): void
  (e: 'delete', formId: string): void
  (e: 'publish', formId: string): void
  (e: 'fill', form: Form): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()
const router = useRouter()

function formatDate(dateString: string): string {
  return new Date(dateString).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric'
  })
}

function handleFillForm() {
  // Navigate to web form with the form version ID
  const formVersionId = props.form.formVersionId || props.form.id
  router.push({ name: 'web-form-new', params: { formVersionId } })
}
</script>

<template>
  <div class="form-card">
    <div class="card-header">
      <h3 class="form-name">{{ form.name }}</h3>
      <span class="status-badge" :class="{ published: form.isPublished, draft: !form.isPublished }">
        {{ form.isPublished ? 'Published' : 'Draft' }}
      </span>
    </div>

    <p class="form-description" v-if="form.description">
      {{ form.description }}
    </p>
    <p class="form-description empty" v-else>No description</p>

    <div class="card-meta">
      <span class="meta-item">
        <span class="label">Version:</span>
        <span class="value">{{ form.version }}</span>
      </span>
      <span class="meta-item">
        <span class="label">Created:</span>
        <span class="value">{{ formatDate(form.createdAt) }}</span>
      </span>
      <span class="meta-item">
        <span class="label">Updated:</span>
        <span class="value">{{ formatDate(form.updatedAt) }}</span>
      </span>
    </div>

    <div class="card-actions">
      <button class="btn btn-primary btn-sm" @click="$emit('manage', form.id)">
        Manage
      </button>
      <button class="btn btn-secondary btn-sm" @click="$emit('edit', form)">
        Edit
      </button>
      <button
        v-if="!form.isPublished"
        class="btn btn-primary btn-sm"
        @click="$emit('publish', form.id)"
      >
        Publish
      </button>
      <button
        v-else
        class="btn btn-success btn-sm"
        @click="handleFillForm"
      >
        Fill Form
      </button>
      <button class="btn btn-danger btn-sm" @click="$emit('delete', form.id)">
        Delete
      </button>
    </div>
  </div>
</template>

<style scoped>
.form-card {
  background: white;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 20px;
  transition: box-shadow 0.2s ease, border-color 0.2s ease;
}

.form-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  border-color: #d1d5db;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
  gap: 12px;
}

.form-name {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #1a1a1a;
  flex: 1;
}

.status-badge {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 9999px;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  flex-shrink: 0;
}

.status-badge.published {
  background-color: #dcfce7;
  color: #166534;
}

.status-badge.draft {
  background-color: #fef3c7;
  color: #92400e;
}

.form-description {
  margin: 0 0 16px;
  color: #4b5563;
  font-size: 14px;
  line-height: 1.5;
}

.form-description.empty {
  color: #9ca3af;
  font-style: italic;
}

.card-meta {
  display: flex;
  gap: 16px;
  margin-bottom: 16px;
  padding-top: 16px;
  border-top: 1px solid #f3f4f6;
  font-size: 13px;
}

.meta-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.meta-item .label {
  color: #9ca3af;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.meta-item .value {
  color: #374151;
  font-weight: 500;
}

.btn-success {
  background: #10b981;
  color: white;
}

.btn-success:hover:not(:disabled) {
  background: #059669;
}

.card-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>