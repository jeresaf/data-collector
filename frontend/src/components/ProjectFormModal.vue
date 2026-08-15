<script setup lang="ts">
import { ref, watch } from 'vue'
import { Project, CreateProjectRequest, UpdateProjectRequest } from '@/stores/project'

interface Props {
  project: Project | null
}

interface Emits {
  (e: 'save', data: CreateProjectRequest | UpdateProjectRequest): void
  (e: 'close'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const form = ref<CreateProjectRequest | UpdateProjectRequest>({
  name: '',
  description: ''
})

const errors = ref<Record<string, string>>({})
const submitting = ref(false)

const isEditing = computed(() => props.project !== null)

watch(
  () => props.project,
  (newProject) => {
    if (newProject) {
      form.value = {
        name: newProject.name,
        description: newProject.description
      }
    } else {
      form.value = { name: '', description: '' }
    }
    errors.value = {}
  },
  { immediate: true }
)

function validate(): boolean {
  errors.value = {}
  let valid = true

  if (!form.value.name.trim()) {
    errors.value.name = 'Project name is required'
    valid = false
  } else if (form.value.name.trim().length < 2) {
    errors.value.name = 'Project name must be at least 2 characters'
    valid = false
  }

  return valid
}

async function handleSubmit() {
  if (!validate()) return

  submitting.value = true
  try {
    emit('save', form.value)
  } finally {
    submitting.value = false
  }
}

function handleClose() {
  emit('close')
}
</script>

<template>
  <div class="modal-overlay" @click.self="handleClose">
    <div class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title">
      <div class="modal-header">
        <h2 id="modal-title">{{ isEditing ? 'Edit Project' : 'Create Project' }}</h2>
        <button class="modal-close" @click="handleClose" aria-label="Close">
          ×
        </button>
      </div>

      <form @submit.prevent="handleSubmit" class="modal-form">
        <div class="form-group">
          <label for="name">Project Name <span class="required">*</span></label>
          <input
            id="name"
            type="text"
            v-model="form.name"
            :class="{ error: errors.name }"
            placeholder="Enter project name"
            maxlength="100"
            disabled="submitting"
          />
          <span v-if="errors.name" class="error-message">{{ errors.name }}</span>
        </div>

        <div class="form-group">
          <label for="description">Description</label>
          <textarea
            id="description"
            v-model="form.description"
            rows="4"
            placeholder="Enter project description (optional)"
            maxlength="500"
            disabled="submitting"
          ></textarea>
          <span class="char-count">{{ form.description.length }}/500</span>
        </div>

        <div class="modal-actions">
          <button type="button" class="btn btn-secondary" @click="handleClose" :disabled="submitting">
            Cancel
          </button>
          <button type="submit" class="btn btn-primary" :disabled="submitting">
            <span v-if="submitting">Saving...</span>
            <span v-else>{{ isEditing ? 'Save Changes' : 'Create Project' }}</span>
          </button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
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

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.modal {
  background: white;
  border-radius: 12px;
  width: 100%;
  max-width: 560px;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
  animation: slideUp 0.2s ease;
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
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
  font-size: 20px;
  font-weight: 600;
  color: #1a1a1a;
}

.modal-close {
  background: none;
  border: none;
  font-size: 24px;
  color: #9ca3af;
  cursor: pointer;
  padding: 4px 8px;
  line-height: 1;
  border-radius: 6px;
  transition: color 0.2s, background 0.2s;
}

.modal-close:hover {
  color: #374151;
  background: #f3f4f6;
}

.modal-form {
  padding: 24px;
}

.form-group {
  margin-bottom: 20px;
}

.form-group label {
  display: block;
  margin-bottom: 6px;
  font-size: 14px;
  font-weight: 500;
  color: #374151;
}

.required {
  color: #ef4444;
}

.form-group input,
.form-group textarea {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 14px;
  color: #1a1a1a;
  transition: border-color 0.2s, box-shadow 0.2s;
  box-sizing: border-box;
}

.form-group input:focus,
.form-group textarea:focus {
  outline: none;
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
}

.form-group input.error,
.form-group textarea.error {
  border-color: #ef4444;
}

.form-group input.error:focus,
.form-group textarea.error:focus {
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.1);
}

.form-group input:disabled,
.form-group textarea:disabled {
  background: #f9fafb;
  color: #9ca3af;
}

.error-message {
  display: block;
  margin-top: 6px;
  font-size: 13px;
  color: #ef4444;
}

.char-count {
  display: block;
  margin-top: 6px;
  font-size: 12px;
  color: #9ca3af;
  text-align: right;
}

.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid #e5e7eb;
}
</style>