import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '@/services/api'

export interface Form {
  id: string
  name: string
  version: string
  description: string
  isPublished: boolean
  createdAt: string
  updatedAt: string
  formVersionId?: string
}

export interface CreateFormRequest {
  name: string
  description: string
  version?: string
}

export interface UpdateFormRequest {
  name?: string
  description?: string
  version?: string
}

export interface UploadFormResponse {
  success: boolean
  message: string
  formId?: string
  formVersionId?: string
  formHash?: string
  xmlns?: string
  version?: string
  title?: string
  errors?: string[]
  warnings?: string[]
  info?: string[]
  sheetHeaders?: Record<string, string[]>
  extraColumns?: Record<string, string[]>
}

interface XmlUploadResponse {
  id: string
  title?: string
  xmlns?: string
  version?: string
  md5Hash?: string
  formVersionId?: string
}

export const useFormStore = defineStore('form', () => {
  const forms = ref<Form[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchForms(projectId: string): Promise<Form[]> {
    loading.value = true
    error.value = null
    try {
      const response = await api.get<Form[] | { content: Form[] }>(`/projects/${projectId}/forms`)
      const result = Array.isArray(response.data) ? response.data : response.data.content
      forms.value = result
      return result
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to fetch forms'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function fetchForm(projectId: string, formId: string): Promise<Form> {
    loading.value = true
    error.value = null
    try {
      const response = await api.get<Form>(`/projects/${projectId}/forms/${formId}`)
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to fetch form'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function createForm(projectId: string, data: CreateFormRequest): Promise<Form> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<Form>(`/projects/${projectId}/forms`, data)
      forms.value.unshift(response.data)
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to create form'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function updateForm(projectId: string, formId: string, data: UpdateFormRequest): Promise<Form> {
    loading.value = true
    error.value = null
    try {
      const response = await api.put<Form>(`/projects/${projectId}/forms/${formId}`, data)
      const index = forms.value.findIndex(f => f.id === formId)
      if (index !== -1) forms.value[index] = response.data
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to update form'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function deleteForm(projectId: string, formId: string): Promise<void> {
    loading.value = true
    error.value = null
    try {
      await api.delete(`/projects/${projectId}/forms/${formId}`)
      forms.value = forms.value.filter(f => f.id !== formId)
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to delete form'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function publishForm(projectId: string, formId: string): Promise<Form> {
    loading.value = true
    error.value = null
    try {
      const response = await api.put<Form>(`/projects/${projectId}/forms/${formId}/publish`)
      const index = forms.value.findIndex(f => f.id === formId)
      if (index !== -1) forms.value[index] = response.data
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to publish form'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function uploadXmlForm(projectId: string, formData: FormData): Promise<UploadFormResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<XmlUploadResponse>(`/projects/${projectId}/forms/upload`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
      await fetchForms(projectId)
      return {
        success: true,
        message: 'XForm XML uploaded successfully',
        formId: response.data.id,
        formVersionId: response.data.formVersionId,
        formHash: response.data.md5Hash,
        xmlns: response.data.xmlns,
        version: response.data.version,
        title: response.data.title,
        errors: [], warnings: [], info: []
      }
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to upload XForm XML'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function uploadXlsForm(projectId: string, formData: FormData): Promise<UploadFormResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<UploadFormResponse>(`/projects/${projectId}/forms/upload-xlsform`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' }
      })
      await fetchForms(projectId)
      return response.data
    } catch (err: any) {
      const responseMessage = err.response?.data?.message
      error.value = responseMessage || 'Failed to convert XLSForm'
      throw err
    } finally {
      loading.value = false
    }
  }

  // Backwards-compatible alias for existing callers: XML was the original upload mode.
  const uploadForm = uploadXmlForm

  function clearError() {
    error.value = null
  }

  return {
    forms,
    loading,
    error,
    fetchForms,
    fetchForm,
    createForm,
    updateForm,
    deleteForm,
    publishForm,
    uploadForm,
    uploadXmlForm,
    uploadXlsForm,
    clearError
  }
})
