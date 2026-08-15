import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import api from '@/services/api'
import { useWebFormWorkflowStore } from './webFormWorkflow'

vi.mock('@/services/api', () => ({
  default: {
    get: vi.fn(),
  },
}))

const getSpy = vi.mocked(api.get)

describe('web form workflow store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getSpy.mockReset()
  })

  it('loads available forms and draft sessions from the workflow API', async () => {
    getSpy
      .mockResolvedValueOnce({
        data: [{
          formId: 'form-1',
          title: 'Household Survey',
          description: null,
          projectId: 'project-1',
          projectName: 'Field Project',
          formVersionId: 'version-1',
          version: '3',
          publishedAt: null,
        }],
      } as any)
      .mockResolvedValueOnce({
        data: [{
          sessionId: 'session-1',
          formId: 'form-1',
          formTitle: 'Household Survey',
          formVersionId: 'version-1',
          formVersion: '3',
          projectId: 'project-1',
          projectName: 'Field Project',
          status: 'draft',
          startedAt: null,
          lastActivityAt: null,
          completedAt: null,
          submittedAt: null,
          submissionId: null,
          submissionInstanceId: null,
        }],
      } as any)

    const store = useWebFormWorkflowStore()

    await store.fetchForms()
    await store.fetchSessions('draft')

    expect(store.forms).toHaveLength(1)
    expect(store.forms[0].formVersionId).toBe('version-1')
    expect(store.drafts).toHaveLength(1)

    expect(getSpy).toHaveBeenNthCalledWith(1, '/web/workflow/forms')
    expect(getSpy).toHaveBeenNthCalledWith(
      2,
      '/web/workflow/sessions',
      { params: { status: 'draft' } },
    )
  })

  it('loads review answers through a session-scoped endpoint', async () => {
    getSpy.mockResolvedValue({
      data: {
        session: {
          sessionId: 'session-1',
          formId: 'form-1',
          formTitle: 'Household Survey',
          formVersionId: 'version-1',
          formVersion: '3',
          projectId: 'project-1',
          projectName: 'Field Project',
          status: 'draft',
          startedAt: null,
          lastActivityAt: null,
          completedAt: null,
          submittedAt: null,
          submissionId: null,
          submissionInstanceId: null,
        },
        answers: [{
          id: 'answer-1',
          questionPath: '/data/name',
          instancePath: '/data/name',
          questionName: 'name',
          questionType: 'string',
          value: 'Amina',
          repeatPath: null,
          repeatIndex: null,
          required: true,
          validationStatus: 'valid',
          constraintMessage: null,
          answeredAt: null,
        }],
        attachments: [],
      },
    } as any)

    const store = useWebFormWorkflowStore()
    const review = await store.fetchReview('session-1')

    expect(review.answers[0].value).toBe('Amina')
    expect(store.review?.session.sessionId).toBe('session-1')
    expect(getSpy).toHaveBeenCalledWith(
      '/web/workflow/sessions/session-1/review',
    )
  })
})
