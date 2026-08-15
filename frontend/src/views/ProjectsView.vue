<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useProjectStore } from '@/stores/project'
import ProjectCard from '@/components/ProjectCard.vue'
import ProjectFormModal from '@/components/ProjectFormModal.vue'

const projectStore = useProjectStore()
const projects = ref<Project[]>([])
const loading = ref(false)
const showCreateModal = ref(false)
const editingProject = ref<Project | null>(null)

interface Project {
  id: string
  name: string
  description: string
  isActive: boolean
  createdAt: string
  updatedAt: string
}

async function loadProjects() {
  loading.value = true
  try {
    projects.value = await projectStore.fetchProjects()
  } catch (error) {
    console.error('Failed to load projects:', error)
  } finally {
    loading.value = false
  }
}

function openCreateModal() {
  editingProject.value = null
  showCreateModal.value = true
}

function openEditModal(project: Project) {
  editingProject.value = project
  showCreateModal.value = true
}

async function handleSave(projectData: Partial<Project>) {
  try {
    if (editingProject.value) {
      await projectStore.updateProject(editingProject.value.id, projectData)
    } else {
      await projectStore.createProject(projectData)
    }
    showCreateModal.value = false
    await loadProjects()
  } catch (error) {
    console.error('Failed to save project:', error)
  }
}

async function handleDelete(projectId: string) {
  if (!confirm('Are you sure you want to delete this project?')) return
  try {
    await projectStore.deleteProject(projectId)
    await loadProjects()
  } catch (error) {
    console.error('Failed to delete project:', error)
  }
}

async function handleArchive(projectId: string) {
  try {
    await projectStore.archiveProject(projectId)
    await loadProjects()
  } catch (error) {
    console.error('Failed to archive project:', error)
  }
}

onMounted(() => {
  loadProjects()
})
</script>

<template>
  <div class="projects-view">
    <div class="page-header">
      <h1>Projects</h1>
      <button class="btn btn-primary" @click="openCreateModal">
        <span class="icon">+</span> New Project
      </button>
    </div>

    <div v-if="loading" class="loading">Loading projects...</div>

    <div v-else-if="projects.length === 0" class="empty-state">
      <p>No projects found. Create your first project to get started.</p>
      <button class="btn btn-primary" @click="openCreateModal">Create Project</button>
    </div>

    <div v-else class="projects-grid">
      <ProjectCard
        v-for="project in projects"
        :key="project.id"
        :project="project"
        @edit="openEditModal"
        @delete="handleDelete"
        @archive="handleArchive"
      />
    </div>

    <ProjectFormModal
      v-if="showCreateModal"
      :project="editingProject"
      @save="handleSave"
      @close="showCreateModal = false"
    />
  </div>
</template>

<style scoped>
.projects-view {
  padding: 24px;
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.page-header h1 {
  margin: 0;
  font-size: 28px;
  font-weight: 600;
  color: #1a1a1a;
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.btn-primary {
  background-color: #2563eb;
  color: white;
}

.btn-primary:hover {
  background-color: #1d4ed8;
}

.btn-secondary {
  background-color: #f3f4f6;
  color: #374151;
}

.btn-secondary:hover {
  background-color: #e5e7eb;
}

.btn-danger {
  background-color: #ef4444;
  color: white;
}

.btn-danger:hover {
  background-color: #dc2626;
}

.btn-sm {
  padding: 6px 12px;
  font-size: 13px;
}

.icon {
  font-size: 16px;
  font-weight: bold;
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

.projects-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 20px;
}
</style>