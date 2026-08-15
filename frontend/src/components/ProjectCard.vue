<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { Project } from '@/stores/project'

interface Props {
  project: Project
}

interface Emits {
  (e: 'edit', project: Project): void
  (e: 'delete', projectId: string): void
  (e: 'archive', projectId: string): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

function formatDate(dateString: string): string {
  return new Date(dateString).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric'
  })
}
</script>

<template>
  <div class="project-card">
    <div class="card-header">
      <h3 class="project-name">{{ project.name }}</h3>
      <span class="status-badge" :class="{ active: project.isActive, inactive: !project.isActive }">
        {{ project.isActive ? 'Active' : 'Archived' }}
      </span>
    </div>

    <p class="project-description" v-if="project.description">
      {{ project.description }}
    </p>
    <p class="project-description empty" v-else>No description</p>

    <div class="card-meta">
      <span class="meta-item">
        <span class="label">Created:</span>
        <span class="value">{{ formatDate(project.createdAt) }}</span>
      </span>
      <span class="meta-item">
        <span class="label">Updated:</span>
        <span class="value">{{ formatDate(project.updatedAt) }}</span>
      </span>
    </div>

    <div class="card-actions">
      <RouterLink
        class="btn btn-primary btn-sm"
        :to="{ name: 'project-detail', params: { id: project.id } }"
        :aria-label="`Open ${project.name}`"
      >
        Open
      </RouterLink>
      <button class="btn btn-secondary btn-sm" @click="$emit('edit', project)">
        Edit
      </button>
      <button
        class="btn btn-sm"
        :class="project.isActive ? 'btn-secondary' : 'btn-primary'"
        @click="$emit('archive', project.id)"
      >
        {{ project.isActive ? 'Archive' : 'Activate' }}
      </button>
      <button class="btn btn-danger btn-sm" @click="$emit('delete', project.id)">
        Delete
      </button>
    </div>
  </div>
</template>

<style scoped>
.project-card {
  background: white;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 20px;
  transition: box-shadow 0.2s ease, border-color 0.2s ease;
}

.project-card:hover {
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

.project-name {
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

.status-badge.active {
  background-color: #dcfce7;
  color: #166534;
}

.status-badge.inactive {
  background-color: #fef2f2;
  color: #991b1b;
}

.project-description {
  margin: 0 0 16px;
  color: #4b5563;
  font-size: 14px;
  line-height: 1.5;
}

.project-description.empty {
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

.card-actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>