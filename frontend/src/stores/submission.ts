import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '@/services/api'

export interface SubmissionListItem {
  id: string
  instanceId: string
  formId: string
  formTitle: string
  formXmlns: string
  formVersionId: string | null
  formVersion: string | null
  status: string
  source: string | null
  submittedAt: string
  submittedById: string | null
  submittedByName: string | null
  reviewedAt: string | null
  reviewedById: string | null
  attachmentCount: number
}

export interface SubmissionDetail {
  id: string
  instanceId: string
  formId: string
  formTitle: string
  formXmlns: string
  formVersionId: string | null
  formVersion: string | null
  xmlContent: string
  jsonContent: string
  status: string
  metadata: Record<string, any>
  submittedAt: string
  submittedById: string | null
  submittedByName: string | null
  reviewedAt: string | null
  reviewedById: string | null
  reviewNotes: string | null
  attachments: AttachmentInfo[]
  answers: AnswerInfo[]
  events: EventInfo[]
}

export interface AttachmentInfo {
  id: string
  fileName: string
  contentType: string
  fileSize: number
  answerName: string | null
}

export interface AnswerInfo {
  id: string
  questionName: string
  questionPath: string
  value: string | null
  repeatIndex: number | null
}

export interface EventInfo {
  id: string
  eventType: string
  eventData: Record<string, any>
  createdAt: string
  createdById: string | null
}

export interface SubmissionFilters {
  projectId?: string
  formId?: string
  formVersionId?: string
  status?: string
  source?: string
  submittedById?: string
  dateFrom?: string
  dateTo?: string
  page?: number
  size?: number
  sort?: string
}

export interface PaginatedResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number
  first: boolean
  last: boolean
  numberOfElements: number
}

export interface UpdateStatusRequest {
  status: string
  reviewNotes?: string
}

export const useSubmissionStore = defineStore('submission', () => {
  const submissions = ref<SubmissionListItem[]>([])
  const currentSubmission = ref<SubmissionDetail | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const pagination = ref({
    totalElements: 0,
    totalPages: 0,
    size: 20,
    number: 0
  })

  async function fetchSubmissions(filters: SubmissionFilters = {}): Promise<PaginatedResponse<SubmissionListItem>> {
    loading.value = true
    error.value = null
    try {
      const params = new URLSearchParams()
      if (filters.projectId) params.append('projectId', filters.projectId)
      if (filters.formId) params.append('formId', filters.formId)
      if (filters.formVersionId) params.append('formVersionId', filters.formVersionId)
      if (filters.status) params.append('status', filters.status)
      if (filters.source) params.append('source', filters.source)
      if (filters.submittedById) params.append('submittedById', filters.submittedById)
      if (filters.dateFrom) params.append('dateFrom', filters.dateFrom)
      if (filters.dateTo) params.append('dateTo', filters.dateTo)
      if (filters.page !== undefined) params.append('page', filters.page.toString())
      if (filters.size !== undefined) params.append('size', filters.size.toString())
      if (filters.sort) params.append('sort', filters.sort)

      const response = await api.get<PaginatedResponse<SubmissionListItem>>(`/submissions?${params.toString()}`)
      submissions.value = response.data.content
      pagination.value = {
        totalElements: response.data.totalElements,
        totalPages: response.data.totalPages,
        size: response.data.size,
        number: response.data.number
      }
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to fetch submissions'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function fetchSubmissionCount(filters: SubmissionFilters = {}): Promise<number> {
    try {
      const params = new URLSearchParams()
      if (filters.projectId) params.append('projectId', filters.projectId)
      if (filters.formId) params.append('formId', filters.formId)
      if (filters.formVersionId) params.append('formVersionId', filters.formVersionId)
      if (filters.status) params.append('status', filters.status)
      if (filters.source) params.append('source', filters.source)
      if (filters.submittedById) params.append('submittedById', filters.submittedById)
      if (filters.dateFrom) params.append('dateFrom', filters.dateFrom)
      if (filters.dateTo) params.append('dateTo', filters.dateTo)

      const response = await api.get<number>(`/submissions/count?${params.toString()}`)
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to fetch submission count'
      throw err
    }
  }

  async function fetchSubmissionDetail(submissionId: string): Promise<SubmissionDetail> {
    loading.value = true
    error.value = null
    try {
      const response = await api.get<SubmissionDetail>(`/submissions/${submissionId}`)
      currentSubmission.value = response.data
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to fetch submission'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function downloadXml(submissionId: string): Promise<void> {
    try {
      const response = await api.get(`/submissions/${submissionId}/xml`, { responseType: 'blob' })
      const blob = new Blob([response.data], { type: 'application/xml' })
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `submission-${submissionId}.xml`
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      window.URL.revokeObjectURL(url)
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to download XML'
      throw err
    }
  }

  async function downloadJson(submissionId: string): Promise<void> {
    try {
      const response = await api.get(`/submissions/${submissionId}/json`, { responseType: 'blob' })
      const blob = new Blob([response.data], { type: 'application/json' })
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `submission-${submissionId}.json`
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      window.URL.revokeObjectURL(url)
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to download JSON'
      throw err
    }
  }

  async function downloadAttachment(submissionId: string, attachmentId: string, fileName: string): Promise<void> {
    try {
      const response = await api.get(`/submissions/${submissionId}/attachments/${attachmentId}/download`, { responseType: 'blob' })
      const blob = new Blob([response.data])
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = fileName
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      window.URL.revokeObjectURL(url)
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to download attachment'
      throw err
    }
  }

  async function updateStatus(submissionId: string, request: UpdateStatusRequest): Promise<SubmissionDetail> {
    loading.value = true
    error.value = null
    try {
      const response = await api.put<SubmissionDetail>(`/submissions/${submissionId}/status`, request)
      currentSubmission.value = response.data
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to update status'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function getFlattenedAnswers(submissionId: string): Promise<Record<string, any>> {
    try {
      const response = await api.get<Record<string, any>>(`/submissions/${submissionId}/answers`)
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to fetch flattened answers'
      throw err
    }
  }

  function clearError() {
    error.value = null
  }

  function clearCurrentSubmission() {
    currentSubmission.value = null
  }

  return {
    submissions,
    currentSubmission,
    loading,
    error,
    pagination,
    fetchSubmissions,
    fetchSubmissionCount,
    fetchSubmissionDetail,
    downloadXml,
    downloadJson,
    downloadAttachment,
    updateStatus,
    getFlattenedAnswers,
    clearError,
    clearCurrentSubmission
  }
})