import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '@/services/api'

export interface AvailableWebForm {
  formId: string
  title: string
  description: string | null
  projectId: string
  projectName: string
  formVersionId: string
  version: string
  publishedAt: string | null
}

export interface WebFormSessionSummary {
  sessionId: string
  formId: string
  formTitle: string
  formVersionId: string
  formVersion: string
  projectId: string
  projectName: string
  status: 'active' | 'draft' | 'submitted'
  startedAt: string | null
  lastActivityAt: string | null
  completedAt: string | null
  submittedAt: string | null
  submissionId: string | null
  submissionInstanceId: string | null
}

export interface WebFormReviewAnswer {
  id: string
  questionPath: string
  instancePath: string | null
  questionName: string | null
  questionType: string | null
  value: unknown
  repeatPath: string | null
  repeatIndex: number | null
  required: boolean
  validationStatus: string
  constraintMessage: string | null
  answeredAt: string | null
}

export interface WebFormReviewAttachment {
  id: string
  answerId: string | null
  questionPath: string | null
  fileName: string
  contentType: string | null
  fileSize: number | null
  contentHash: string | null
  createdAt: string | null
}

export interface WebFormSessionReview {
  session: WebFormSessionSummary
  answers: WebFormReviewAnswer[]
  attachments: WebFormReviewAttachment[]
}

function messageFor(err: any, fallback: string): string {
  return err?.response?.data?.message
    || err?.response?.data?.error
    || fallback
}

export const useWebFormWorkflowStore = defineStore('webFormWorkflow', () => {
  const forms = ref<AvailableWebForm[]>([])
  const drafts = ref<WebFormSessionSummary[]>([])
  const submitted = ref<WebFormSessionSummary[]>([])
  const review = ref<WebFormSessionReview | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchForms(): Promise<AvailableWebForm[]> {
    loading.value = true
    error.value = null
    try {
      const response = await api.get<AvailableWebForm[]>('/web/workflow/forms')
      forms.value = response.data
      return response.data
    } catch (err: any) {
      error.value = messageFor(err, 'Unable to load available forms.')
      throw err
    } finally {
      loading.value = false
    }
  }

  async function fetchForm(formId: string): Promise<AvailableWebForm> {
    const response = await api.get<AvailableWebForm>(`/web/workflow/forms/${formId}`)
    return response.data
  }

  async function fetchSessions(
    status: 'active' | 'draft' | 'submitted',
  ): Promise<WebFormSessionSummary[]> {
    loading.value = true
    error.value = null
    try {
      const response = await api.get<WebFormSessionSummary[]>(
        '/web/workflow/sessions',
        { params: { status } },
      )
      if (status === 'draft') drafts.value = response.data
      if (status === 'submitted') submitted.value = response.data
      return response.data
    } catch (err: any) {
      error.value = messageFor(err, `Unable to load ${status} forms.`)
      throw err
    } finally {
      loading.value = false
    }
  }

  async function fetchReview(sessionId: string): Promise<WebFormSessionReview> {
    loading.value = true
    error.value = null
    try {
      const response = await api.get<WebFormSessionReview>(
        `/web/workflow/sessions/${sessionId}/review`,
      )
      review.value = response.data
      return response.data
    } catch (err: any) {
      error.value = messageFor(err, 'Unable to load the form review.')
      throw err
    } finally {
      loading.value = false
    }
  }

  function clearError() {
    error.value = null
  }

  function clearReview() {
    review.value = null
  }

  return {
    forms,
    drafts,
    submitted,
    review,
    loading,
    error,
    fetchForms,
    fetchForm,
    fetchSessions,
    fetchReview,
    clearError,
    clearReview,
  }
})
