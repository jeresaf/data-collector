<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { Form } from '@/stores/form'

interface Props {
  form: Form | null
}

interface Emits {
  (e: 'save', data: Partial<Form>): void
  (e: 'close'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const formData = ref<Partial<Form>>({
  name: '',
  description: '',
  version: '1.0.0'
})

const errors = ref<Record<string, string>>({})

const isEditing = computed(() => props.form !== null)

function validate(): boolean {
  errors.value = {}
  let valid = true

  if (!formData.value.name?.trim()) {
    errors.value.name = 'Form name is required'
    valid = false
  }

  return valid
}

function handleSubmit() {
  if (validate()) {
    emit('save', formData.value)
  }
}

function resetForm() {
  formData.value = {
    name: '',
    description: '',
    version: '1.0.0'
  }
  errors.value = {}
}

function populateForm() {
  if (props.form) {
    formData.value = {
      name: props.form.name,
      description: props.form.description,
      version: props.form.version
    }
  }
}

watch(() => props.form, () => {
  populateForm()
}, { immediate: true, deep: true })
</script>

<template>
  <div class="modal-overlay" @click.self="$emit('close')">
    <div class="modal" @click.stop>
      <div class="modal-header">
        <h2>{{ isEditing ? 'Edit Form' : 'Create Form' }}</h2>
        <button class="modal-close" @click="$emit('close')">&times;</button>
      </div>

      <form class="modal-form" @submit.prevent="handleSubmit">
        <div class="form-group">
          <label for="name">Form Name <span class="required">*</span></label>
          <input
            id="name"
            type="text"
            v-model="formData.name"
            :class="{ error: errors.name }"
            placeholder="Enter form name"
            maxlength="255"
          />
          <span v-if="errors.name" class="error-message">{{ errors.name }}</span>
        </div>

        <div class="form-group">
          <label for="description">Description</label>
          <textarea
            id="description"
            v-model="formData.description"
            rows="4"
            placeholder="Enter form description (optional)"
            maxlength="1000"
          ></textarea>
          <span class="char-count">{{ (formData.description || '').length }}/1000</span>
        </div>

        <div class="form-group" v-if="isEditing">
          <label for="version">Version</label>
          <input
            id="version"
            type="text"
            v-model="formData.version"
            placeholder="e.g., 1.0.0"
            maxlength="20"
          />
        </div>

        <div class="modal-actions">
          <button type="button" class="btn btn-secondary" @click="$emit('close')">
            Cancel
          </button>
          <button type="submit" class="btn btn-primary">
            {{ isEditing ? 'Save Changes' : 'Create Form' }}
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