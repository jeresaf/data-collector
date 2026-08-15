import { ref } from 'vue'
import { defineStore } from 'pinia'
import api from '@/services/api'

export interface FormLifecycleForm {
  id: string
  name: string
  title?: string
  description?: string | null
  xmlns?: string | null
  version?: string | null
  isPublished: boolean
  isActive: boolean
  formVersionId?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface FormVersionSummary {
  id: string
  version: string
  status: string
  isPublished: boolean
  isLatest: boolean
  changelog?: string | null
  publishedAt?: string | null
  validatedAt?: string | null
  deprecatedAt?: string | null
  archivedAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface FormPublishHistoryEntry {
  id: string
  formVersionId: string
  version: string
  action: string
  previousVersionId?: string | null
  previousVersion?: string | null
  performedById: string
  performedByUsername: string
  performedByName: string
  performedAt: string
  notes?: string | null
}

export const useFormLifecycleStore = defineStore('formLifecycle', () => {
  const form = ref<FormLifecycleForm | null>(null)
  const versions = ref<FormVersionSummary[]>([])
  const history = ref<FormPublishHistoryEntry[]>([])
  const loading = ref(false)
  const saving = ref(false)
  const error = ref<string | null>(null)

  function messageFor(err: any, fallback: string): string {
    return err?.response?.data?.message || err?.response?.data?.error || fallback
  }

  async function fetchForm(projectId: string, formId: string): Promise<FormLifecycleForm> {
    const response = await api.get<FormLifecycleForm>(`/projects/${projectId}/forms/${formId}`)
    form.value = response.data
    return response.data
  }

  async function fetchVersions(projectId: string, formId: string): Promise<FormVersionSummary[]> {
    const response = await api.get<FormVersionSummary[]>(
      `/projects/${projectId}/forms/${formId}/lifecycle/versions`,
    )
    versions.value = response.data
    return response.data
  }

  async function fetchHistory(projectId: string, formId: string): Promise<FormPublishHistoryEntry[]> {
    const response = await api.get<FormPublishHistoryEntry[]>(
      `/projects/${projectId}/forms/${formId}/lifecycle/history`,
    )
    history.value = response.data
    return response.data
  }

  async function load(projectId: string, formId: string): Promise<void> {
    loading.value = true
    error.value = null
    try {
      await Promise.all([
        fetchForm(projectId, formId),
        fetchVersions(projectId, formId),
        fetchHistory(projectId, formId),
      ])
    } catch (err: any) {
      error.value = messageFor(err, 'Unable to load form lifecycle details.')
      throw err
    } finally {
      loading.value = false
    }
  }

  async function publishVersion(projectId: string, formId: string, versionId: string): Promise<void> {
    saving.value = true
    error.value = null
    try {
      await api.post(
        `/projects/${projectId}/forms/${formId}/publish`,
        null,
        { params: { versionId } },
      )
      await load(projectId, formId)
    } catch (err: any) {
      error.value = messageFor(err, 'Unable to publish the selected form version.')
      throw err
    } finally {
      saving.value = false
    }
  }

  async function archiveForm(projectId: string, formId: string): Promise<void> {
    saving.value = true
    error.value = null
    try {
      await api.post(`/projects/${projectId}/forms/${formId}/archive`)
      await load(projectId, formId)
    } catch (err: any) {
      error.value = messageFor(err, 'Unable to archive this form.')
      throw err
    } finally {
      saving.value = false
    }
  }

  function clear(): void {
    form.value = null
    versions.value = []
    history.value = []
    error.value = null
  }

  return {
    form,
    versions,
    history,
    loading,
    saving,
    error,
    fetchForm,
    fetchVersions,
    fetchHistory,
    load,
    publishVersion,
    archiveForm,
    clear,
  }
})
