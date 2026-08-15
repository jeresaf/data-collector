<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useFormStore } from '@/stores/form'
import FormCard from '@/components/FormCard.vue'
import FormFormModal from '@/components/FormFormModal.vue'
import FormUploadModal from '@/components/FormUploadModal.vue'

interface Props {
  projectId: string
}

const props = defineProps<Props>()
const formStore = useFormStore()
const router = useRouter()

const forms = ref<Form[]>([])
const loading = ref(false)
const showCreateModal = ref(false)
const editingForm = ref<Form | null>(null)
const showUploadModal = ref(false)

interface Form {
  id: string
  name: string
  version: string
  description: string
  isPublished: boolean
  createdAt: string
  updatedAt: string
}

async function loadForms() {
  loading.value = true
  try {
    forms.value = await formStore.fetchForms(props.projectId)
  } catch (error) {
    console.error('Failed to load forms:', error)
  } finally {
    loading.value = false
  }
}

function openCreateModal() {
  editingForm.value = null
  showCreateModal.value = true
}

function openEditModal(form: Form) {
  editingForm.value = form
  showCreateModal.value = true
}

async function handleSave(formData: Partial<Form>) {
  try {
    if (editingForm.value) {
      await formStore.updateForm(props.projectId, editingForm.value.id, formData)
    } else {
      await formStore.createForm(props.projectId, formData)
    }
    showCreateModal.value = false
    await loadForms()
  } catch (error) {
    console.error('Failed to save form:', error)
  }
}

async function handleDelete(formId: string) {
  if (!confirm('Are you sure you want to delete this form?')) return
  try {
    await formStore.deleteForm(props.projectId, formId)
    await loadForms()
  } catch (error) {
    console.error('Failed to delete form:', error)
  }
}

function handlePublish(formId: string) {
  router.push({
    name: 'form-detail',
    params: { projectId: props.projectId, formId },
  })
}

async function handleUploaded() {
  await loadForms()
}

onMounted(() => {
  loadForms()
})
</script>

<template>
  <div class="form-list">
    <div class="list-header">
      <h2>Forms</h2>
      <div class="header-actions">
        <button class="btn btn-secondary" @click="showUploadModal = true">
          <span class="icon">⬆</span> Upload Form
        </button>
        <button class="btn btn-primary" @click="openCreateModal">
          <span class="icon">+</span> New Form
        </button>
      </div>
    </div>

    <div v-if="loading" class="loading">Loading forms...</div>

    <div v-else-if="forms.length === 0" class="empty-state">
      <p>No forms found. Create your first form to get started.</p>
      <button class="btn btn-primary" @click="openCreateModal">Create Form</button>
    </div>

    <div v-else class="forms-grid">
      <FormCard
        v-for="form in forms"
        :key="form.id"
        :form="form"
        @edit="openEditModal"
        @delete="handleDelete"
        @publish="handlePublish"
      />
    </div>

    <FormFormModal
      v-if="showCreateModal"
      :form="editingForm"
      @save="handleSave"
      @close="showCreateModal = false"
    />

    <FormUploadModal
      v-if="showUploadModal"
      :project-id="props.projectId"
      @uploaded="handleUploaded"
      @close="showUploadModal = false"
    />
  </div>
</template>

<style scoped>
.form-list {
  padding: 0;
}

.list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.list-header h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  color: #1a1a1a;
}

.header-actions {
  display: flex;
  gap: 12px;
}

.forms-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 20px;
}

.loading {
  text-align: center;
  padding: 48px;
  color: #6b7280;
  font-size: 16px;
}

.empty-state {
  text-align: center;
  padding: 64px 24px;
  background: #f9fafb;
  border-radius: 12px;
  border: 1px dashed #d1d5db;
}

.empty-state p {
  margin: 0 0 16px;
  color: #6b7280;
  font-size: 16px;
}
</style>
