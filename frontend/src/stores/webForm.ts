import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '@/services/api'

export interface WebFormSession {
  id: string
  formVersionId: string
  status: string
  currentScreen: string
  startedAt: string
  lastActivityAt: string
  completedAt: string | null
}

export interface GroupContextResponse {
  label: string
  path: string | null
  instancePath: string | null
  repeat: boolean
  repeatIndex: number | null
  depth: number
  appearanceHint?: string | null
}

export interface QuestionResponse {
  index: number
  name: string
  path: string
  instancePath: string | null
  groupHierarchy: GroupContextResponse[]
  repeatPath: string | null
  repeatIndex: number | null
  label: string
  hint: string
  dataType: number
  controlType: number
  questionType?: string | null
  required: boolean
  readOnly: boolean
  constraintText: string
  appearanceHint: string
  mediaType?: string | null
  promptAudioFile?: string | null
  promptImageFile?: string | null
  promptVideoFile?: string | null
  currentAnswer: string | null
  choices: SelectChoice[]
  validationStatus?: string
  constraintMessage?: string | null
}

export interface SelectChoice {
  value: string
  label: string
}

export interface GroupResponse {
  label: string
  path: string | null
  instancePath: string | null
  depth: number
  hierarchy: GroupContextResponse[]
  isRepeat: boolean
  repeatCount: number
  currentRepeatIndex: number | null
  repeatTargetCount: number | null
  repeatCountControlled: boolean
  canAddRepeat: boolean
  canDeleteRepeat: boolean
  appearanceHint?: string | null
}

export interface FieldListResponse {
  groupPath: string | null
  groupInstancePath: string | null
  questions: QuestionResponse[]
}

export interface RepeatJunctureResponse {
  label: string
  path: string | null
  instancePath: string | null
  depth: number
  hierarchy: GroupContextResponse[]
  repeatCount: number
  currentRepeatIndex: number | null
  repeatTargetCount: number | null
  repeatCountControlled: boolean
  canAddRepeat: boolean
  canDeleteRepeat: boolean
}

export interface CurrentScreenResponse {
  event: number
  isAtBeginning: boolean
  isAtEnd: boolean
  question: QuestionResponse | null
  group: GroupResponse | null
  repeatJuncture: RepeatJunctureResponse | null
}

export interface ProgressResponse {
  answeredQuestions: number
  totalQuestions: number
  percent: number
}

export interface StartSessionRequest {
  userId?: string
  appUserId?: string
}

export interface StartSessionResponse {
  sessionId: string
  formVersionId: string
  status: string
  currentEvent: number
  isAtBeginning: boolean
  isAtEnd: boolean
  question: QuestionResponse | null
  group: GroupResponse | null
  repeatJuncture: RepeatJunctureResponse | null
}

export interface SaveAnswerRequest {
  questionPath: string
  instancePath?: string
  value: any
  repeatPath?: string
  repeatIndex?: number
}

export interface AnswerResponse {
  answerId: string
  questionPath: string
  instancePath: string | null
  questionName: string
  value: any
  isRelevant: boolean
  isRequired: boolean
  validationStatus: string
  constraintMessage: string
  currentEvent: number
  isAtEnd: boolean
  nextQuestion: QuestionResponse | null
  nextGroup: GroupResponse | null
  nextRepeatJuncture: RepeatJunctureResponse | null
}

export interface NavigationResponse {
  event: number
  isAtBeginning: boolean
  isAtEnd: boolean
  success: boolean
  question: QuestionResponse | null
  group: GroupResponse | null
  repeatJuncture: RepeatJunctureResponse | null
}

export interface FinalizeResponse {
  success: boolean
  submissionId: string | null
  instanceId: string | null
  message: string
  validation?: ValidationResponse | null
}

export interface FormDefinitionResponse {
  formVersionId: string
  title: string
  xmlFormId: string
  availableLocales: string[]
  compatibilityWarnings: string[]
}

export interface UploadAttachmentRequest {
  answerId?: string
  questionPath: string
  fileName: string
  contentType: string
  fileSize: number
  content: number[]
}

export interface AttachmentResponse {
  attachmentId: string
  fileName: string
  contentType: string
  fileSize: number
  storagePath: string
  contentHash: string
}

export interface ValidationResponse {
  valid: boolean
  failedPromptIndex: string | null
  outcomeCode: number
  message: string | null
  failedQuestion: QuestionResponse | null
}

// JavaRosa 4.3.2 FormEntryController event constants
export const EVENT_BEGINNING_OF_FORM = 0
export const EVENT_END_OF_FORM = 1
export const EVENT_PROMPT_NEW_REPEAT = 2
export const EVENT_QUESTION = 4
export const EVENT_GROUP = 8
export const EVENT_REPEAT = 16
export const EVENT_REPEAT_JUNCTURE = 32

// JavaRosa control type constants
export const CONTROL_INPUT = 1
export const CONTROL_SELECT_ONE = 2
export const CONTROL_SELECT_MULTI = 3
export const CONTROL_TRIGGER = 4
export const CONTROL_UPLOAD = 5
export const CONTROL_GROUP = 6
export const CONTROL_REPEAT = 7
export const CONTROL_LABEL = 8
export const CONTROL_HIDDEN = 9

// JavaRosa data type constants
export const DATATYPE_TEXT = 1
export const DATATYPE_INTEGER = 2
export const DATATYPE_DECIMAL = 3
export const DATATYPE_DATE = 4
export const DATATYPE_TIME = 5
export const DATATYPE_DATETIME = 6
export const DATATYPE_GEOPOINT = 7
export const DATATYPE_GEOSHAPE = 8
export const DATATYPE_GEOTRACE = 9
export const DATATYPE_BINARY = 10
export const DATATYPE_BARCODE = 11

export const useWebFormStore = defineStore('webForm', () => {
  const currentSession = ref<WebFormSession | null>(null)
  const currentScreen = ref<CurrentScreenResponse | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const formDefinition = ref<FormDefinitionResponse | null>(null)
  const progress = ref<ProgressResponse | null>(null)

  async function startSession(formVersionId: string, request: StartSessionRequest = {}): Promise<StartSessionResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<StartSessionResponse>(`/web/forms/${formVersionId}/sessions`, request)
      currentSession.value = {
        id: response.data.sessionId,
        formVersionId: response.data.formVersionId,
        status: response.data.status,
        currentScreen: 'start',
        startedAt: new Date().toISOString(),
        lastActivityAt: new Date().toISOString(),
        completedAt: null
      }
      currentScreen.value = {
        event: response.data.currentEvent,
        isAtBeginning: response.data.isAtBeginning,
        isAtEnd: response.data.isAtEnd,
        question: response.data.question,
        group: response.data.group,
        repeatJuncture: response.data.repeatJuncture
      }
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to start session'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function getSession(sessionId: string): Promise<WebFormSession> {
    loading.value = true
    error.value = null
    try {
      const response = await api.get<WebFormSession>(`/web/sessions/${sessionId}`)
      currentSession.value = response.data
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to get session'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function getCurrentScreen(sessionId: string): Promise<CurrentScreenResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.get<CurrentScreenResponse>(`/web/sessions/${sessionId}/current`)
      currentScreen.value = response.data
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to get current screen'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function saveAnswer(sessionId: string, request: SaveAnswerRequest): Promise<AnswerResponse> {
    // Do not toggle the global loading flag for answer autosaves. A text input
    // emits change while the user is clicking Next; disabling the Next button
    // between mousedown and click causes the browser to cancel that click and
    // makes the UI appear to require two clicks. WebFormView serializes saves
    // and awaits the queue before navigation instead.
    try {
      const response = await api.post<AnswerResponse>(`/web/sessions/${sessionId}/answers`, request)

      // Saving an answer must not navigate the visible form. Navigation is
      // owned exclusively by the navigate* actions.
      return response.data
    } catch (err: any) {
      // Required/constraint failures are field-level validation errors and are
      // rendered beside the control by WebFormView.
      if (err.response?.status !== 400) {
        error.value = err.response?.data?.message || err.response?.data?.error || 'Failed to save answer'
      }
      throw err
    }
  }

  async function navigateNext(sessionId: string): Promise<NavigationResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<NavigationResponse>(`/web/sessions/${sessionId}/navigate/next`)
      currentScreen.value = {
        event: response.data.event,
        isAtBeginning: response.data.isAtBeginning,
        isAtEnd: response.data.isAtEnd,
        question: response.data.question,
        group: response.data.group,
        repeatJuncture: response.data.repeatJuncture
      }
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to navigate next'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function navigatePrevious(sessionId: string): Promise<NavigationResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<NavigationResponse>(`/web/sessions/${sessionId}/navigate/previous`)
      currentScreen.value = {
        event: response.data.event,
        isAtBeginning: response.data.isAtBeginning,
        isAtEnd: response.data.isAtEnd,
        question: response.data.question,
        group: response.data.group,
        repeatJuncture: response.data.repeatJuncture
      }
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to navigate previous'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function descendIntoRepeat(sessionId: string, repeatIndex: number): Promise<NavigationResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<NavigationResponse>(`/web/sessions/${sessionId}/repeats/${repeatIndex}/descend`)
      currentScreen.value = {
        event: response.data.event,
        isAtBeginning: response.data.isAtBeginning,
        isAtEnd: response.data.isAtEnd,
        question: response.data.question,
        group: response.data.group,
        repeatJuncture: response.data.repeatJuncture
      }
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to descend into repeat'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function createNewRepeat(sessionId: string): Promise<NavigationResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<NavigationResponse>(`/web/sessions/${sessionId}/repeats/new`)
      currentScreen.value = {
        event: response.data.event,
        isAtBeginning: response.data.isAtBeginning,
        isAtEnd: response.data.isAtEnd,
        question: response.data.question,
        group: response.data.group,
        repeatJuncture: response.data.repeatJuncture
      }
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to create new repeat'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function deleteRepeat(sessionId: string): Promise<NavigationResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<NavigationResponse>(`/web/sessions/${sessionId}/repeats/delete`)
      currentScreen.value = {
        event: response.data.event,
        isAtBeginning: response.data.isAtBeginning,
        isAtEnd: response.data.isAtEnd,
        question: response.data.question,
        group: response.data.group,
        repeatJuncture: response.data.repeatJuncture
      }
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to delete repeat'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function getFieldList(sessionId: string): Promise<FieldListResponse> {
    const response = await api.get<FieldListResponse>(`/web/sessions/${sessionId}/field-list`)
    return response.data
  }

  async function navigateFieldListNext(sessionId: string, groupInstancePath: string): Promise<NavigationResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<NavigationResponse>(`/web/sessions/${sessionId}/field-list/navigate/next`, { groupInstancePath })
      currentScreen.value = {
        event: response.data.event,
        isAtBeginning: response.data.isAtBeginning,
        isAtEnd: response.data.isAtEnd,
        question: response.data.question,
        group: response.data.group,
        repeatJuncture: response.data.repeatJuncture
      }
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || err.response?.data?.error || 'Failed to leave field-list group'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function navigateFieldListPrevious(sessionId: string, groupInstancePath: string): Promise<NavigationResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<NavigationResponse>(`/web/sessions/${sessionId}/field-list/navigate/previous`, { groupInstancePath })
      currentScreen.value = {
        event: response.data.event,
        isAtBeginning: response.data.isAtBeginning,
        isAtEnd: response.data.isAtEnd,
        question: response.data.question,
        group: response.data.group,
        repeatJuncture: response.data.repeatJuncture
      }
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || err.response?.data?.error || 'Failed to leave field-list group'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function getProgress(sessionId: string): Promise<ProgressResponse> {
    try {
      const response = await api.get<ProgressResponse>(`/web/sessions/${sessionId}/progress`)
      progress.value = response.data
      return response.data
    } catch (err: any) {
      // Progress must never replace the primary form error state.
      console.error('Failed to load web-form progress:', err)
      throw err
    }
  }

  async function finalizeSession(sessionId: string): Promise<FinalizeResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<FinalizeResponse>(`/web/sessions/${sessionId}/finalize`)
      if (response.data.success && currentSession.value) {
        currentSession.value.status = 'submitted'
        currentSession.value.completedAt = new Date().toISOString()
      }
      return response.data
    } catch (err: any) {
      const response = err.response?.data as FinalizeResponse | undefined
      if (response && response.success === false) {
        return response
      }
      error.value = err.response?.data?.message || 'Failed to finalize session'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function saveDraft(sessionId: string): Promise<WebFormSession> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<WebFormSession>(`/web/sessions/${sessionId}/draft`)
      currentSession.value = response.data
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to save draft'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function uploadAttachment(sessionId: string, request: UploadAttachmentRequest): Promise<AttachmentResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<AttachmentResponse>(`/web/sessions/${sessionId}/attachments`, request)
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to upload attachment'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function validateForm(sessionId: string): Promise<ValidationResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<ValidationResponse>(`/web/sessions/${sessionId}/validate`)
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to validate form'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function getFormDefinition(formVersionId: string): Promise<FormDefinitionResponse> {
    loading.value = true
    error.value = null
    try {
      const response = await api.get<FormDefinitionResponse>(`/web/forms/${formVersionId}/definition`)
      formDefinition.value = response.data
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to get form definition'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function deleteSession(sessionId: string): Promise<void> {
    loading.value = true
    error.value = null
    try {
      await api.delete(`/web/sessions/${sessionId}`)
      currentSession.value = null
      currentScreen.value = null
      progress.value = null
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to delete session'
      throw err
    } finally {
      loading.value = false
    }
  }

  function clearError() {
    error.value = null
  }

  function clearSession() {
    currentSession.value = null
    currentScreen.value = null
    progress.value = null
  }

  return {
    currentSession,
    currentScreen,
    loading,
    error,
    formDefinition,
    progress,
    startSession,
    getSession,
    getCurrentScreen,
    saveAnswer,
    navigateNext,
    navigatePrevious,
    descendIntoRepeat,
    createNewRepeat,
    deleteRepeat,
    getFieldList,
    navigateFieldListNext,
    navigateFieldListPrevious,
    getProgress,
    finalizeSession,
    saveDraft,
    uploadAttachment,
    validateForm,
    getFormDefinition,
    deleteSession,
    clearError,
    clearSession
  }
})