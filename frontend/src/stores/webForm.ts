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
  required: boolean
  readOnly: boolean
  constraintText: string
  appearanceHint: string
  mediaType?: string | null
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
  submissionId: string
  instanceId: string
  message: string
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
    loading.value = true
    error.value = null
    try {
      const response = await api.post<AnswerResponse>(`/web/sessions/${sessionId}/answers`, request)
      
      // Update current screen with next question/group
      currentScreen.value = {
        event: response.data.currentEvent,
        isAtBeginning: false, // Will be updated from next navigation
        isAtEnd: response.data.isAtEnd,
        question: response.data.nextQuestion,
        group: response.data.nextGroup,
        repeatJuncture: response.data.nextRepeatJuncture
      }
      
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to save answer'
      throw err
    } finally {
      loading.value = false
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
  }

  return {
    currentSession,
    currentScreen,
    loading,
    error,
    formDefinition,
    startSession,
    getSession,
    getCurrentScreen,
    saveAnswer,
    navigateNext,
    navigatePrevious,
    descendIntoRepeat,
    createNewRepeat,
    deleteRepeat,
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