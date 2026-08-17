<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/project'
import { useFormStore } from '@/stores/form'
import { useSubmissionStore } from '@/stores/submission'
import ProjectFormModal from '@/components/ProjectFormModal.vue'
import FormList from '@/components/FormList.vue'
import SubmissionList from '@/components/SubmissionList.vue'
import ReferenceDataPanel from '@/components/ReferenceDataPanel.vue'
import ReportBuilderPanel from '@/components/ReportBuilderPanel.vue'
import AuditTrailPanel from '@/components/AuditTrailPanel.vue'
import ProjectSettingsPanel from '@/components/ProjectSettingsPanel.vue'
import ProjectMembersPanel from '@/components/ProjectMembersPanel.vue'

const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const formStore = useFormStore()
const submissionStore = useSubmissionStore()

const project = ref<Project | null>(null)
const loading = ref(false)
const activeTab = ref<'forms' | 'submissions' | 'reference' | 'reports' | 'audit' | 'access' | 'settings'>('forms')
const showEditModal = ref(false)

const projectId = computed(() => {
  const value = route.params.id
  if (Array.isArray(value)) return value[0] ?? ''
  return typeof value === 'string' ? value : ''
})

interface Project {
  id: string
  name: string
  description: string
  isActive: boolean
  createdAt: string
  updatedAt: string
}

async function loadProject() {
  const id = projectId.value
  if (!id) {
    console.error('Cannot load project: route is missing a project id')
    router.push('/')
    return
  }

  loading.value = true
  try {
    const fetchedProject = await projectStore.fetchProject(id)

    // The route identifies the project being viewed. Keep that ID authoritative so
    // project-scoped requests never degrade into /projects/undefined/... even if a
    // backend response is incomplete or changes shape.
    project.value = { ...fetchedProject, id }

    await Promise.all([
      formStore.fetchForms(id),
      submissionStore.fetchSubmissions({ projectId: id })
    ])
  } catch (error) {
    console.error('Failed to load project:', error)
    router.push('/')
  } finally {
    loading.value = false
  }
}

async function handleSave(projectData: Partial<Project>) {
  try {
    const updatedProject = await projectStore.updateProject(projectId.value, projectData)
    project.value = { ...project.value!, ...updatedProject, id: projectId.value }
    showEditModal.value = false
  } catch (error) {
    console.error('Failed to update project:', error)
  }
}

async function handleArchive() {
  if (!confirm('Are you sure you want to archive this project?')) return
  try {
    await projectStore.archiveProject(projectId.value)
    router.push('/')
  } catch (error) {
    console.error('Failed to archive project:', error)
  }
}

async function handleDelete() {
  if (!confirm('Are you sure you want to delete this project? This action cannot be undone.')) return
  try {
    await projectStore.deleteProject(projectId.value)
    router.push('/')
  } catch (error) {
    console.error('Failed to delete project:', error)
  }
}

const formattedCreatedAt = computed(() => {
  if (!project.value?.createdAt) return ''
  return new Date(project.value.createdAt).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'long',
    day: 'numeric'
  })
})

onMounted(() => {
  loadProject()
})
</script>

<template>
  <div v-if="loading" class="loading">Loading project...</div>

  <div v-else-if="project" class="project-detail-view">
    <div class="project-header">
      <div class="project-info">
        <h1>{{ project.name }}</h1>
        <p v-if="project.description" class="description">{{ project.description }}</p>
        <div class="meta">
          <span class="status-badge" :class="{ active: project.isActive, archived: !project.isActive }">
            {{ project.isActive ? 'Active' : 'Archived' }}
          </span>
          <span class="created">Created {{ formattedCreatedAt }}</span>
        </div>
      </div>
      <div class="project-actions">
        <button class="btn btn-secondary" @click="showEditModal = true">Edit</button>
        <button v-if="project.isActive" class="btn btn-secondary" @click="handleArchive">Archive</button>
        <button class="btn btn-danger" @click="handleDelete">Delete</button>
      </div>
    </div>

    <div class="tabs">
      <button
        class="tab"
        :class="{ active: activeTab === 'forms' }"
        @click="activeTab = 'forms'"
      >
        Forms
      </button>
      <button
        class="tab"
        :class="{ active: activeTab === 'submissions' }"
        @click="activeTab = 'submissions'"
      >
        Submissions
      </button>
      <button
        class="tab"
        :class="{ active: activeTab === 'reference' }"
        @click="activeTab = 'reference'"
      >
        Reference Data
      </button>
      <button
        class="tab"
        :class="{ active: activeTab === 'reports' }"
        @click="activeTab = 'reports'"
      >
        Reports
      </button>
      <button
        class="tab"
        :class="{ active: activeTab === 'audit' }"
        @click="activeTab = 'audit'"
      >
        Audit
      </button>
      <button
        class="tab"
        :class="{ active: activeTab === 'access' }"
        @click="activeTab = 'access'"
      >
        Users & Access
      </button>
      <button
        class="tab"
        :class="{ active: activeTab === 'settings' }"
        @click="activeTab = 'settings'"
      >
        Settings
      </button>
    </div>

    <div class="tab-content">
      <FormList v-if="activeTab === 'forms'" :project-id="projectId" />
      <SubmissionList v-else-if="activeTab === 'submissions'" :project-id="projectId" />
      <ReferenceDataPanel v-else-if="activeTab === 'reference'" :project-id="projectId" />
      <ReportBuilderPanel v-else-if="activeTab === 'reports'" :project-id="projectId" />
      <AuditTrailPanel v-else-if="activeTab === 'audit'" />
      <ProjectMembersPanel v-else-if="activeTab === 'access'" :project-id="projectId" />
      <ProjectSettingsPanel v-else :project-id="projectId" />
    </div>

    <ProjectFormModal
      v-if="showEditModal"
      :project="project"
      @save="handleSave"
      @close="showEditModal = false"
    />
  </div>

  <div v-else class="not-found">
    <h2>Project not found</h2>
    <button class="btn btn-primary" @click="router.push('/')">Back to Projects</button>
  </div>
</template>

<style scoped>
.project-detail-view {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
}

.project-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
  padding-bottom: 24px;
  border-bottom: 1px solid #e5e7eb;
}

.project-info h1 {
  margin: 0 0 8px;
  font-size: 28px;
  font-weight: 600;
  color: #1a1a1a;
}

.description {
  margin: 0 0 12px;
  color: #4b5563;
  font-size: 16px;
}

.meta {
  display: flex;
  align-items: center;
  gap: 16px;
  font-size: 14px;
  color: #6b7280;
}

.status-badge {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 9999px;
  font-size: 12px;
  font-weight: 500;
}

.status-badge.active {
  background-color: #dcfce7;
  color: #166534;
}

.status-badge.archived {
  background-color: #f3f4f6;
  color: #374151;
}

.project-actions {
  display: flex;
  gap: 12px;
}

.tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 24px;
  border-bottom: 1px solid #e5e7eb;
  padding-bottom: 4px;
}

.tab {
  padding: 12px 20px;
  border: none;
  background: none;
  font-size: 14px;
  font-weight: 500;
  color: #6b7280;
  cursor: pointer;
  border-radius: 6px 6px 0 0;
  transition: all 0.2s ease;
}

.tab:hover {
  color: #374151;
  background-color: #f3f4f6;
}

.tab.active {
  color: #2563eb;
  background-color: #eff6ff;
}

.tab-content {
  min-height: 400px;
}

.not-found {
  text-align: center;
  padding: 64px 24px;
}

.not-found h2 {
  margin: 0 0 16px;
  color: #1a1a1a;
}

.loading {
  text-align: center;
  padding: 48px;
  color: #6b7280;
  font-size: 16px;
}
</style>
