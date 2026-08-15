import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSubmissionStore } from './submission'

const api = vi.hoisted(() => ({
  get: vi.fn(),
  put: vi.fn()
}))

vi.mock('@/services/api', () => ({ default: api }))

const page = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  size: 20,
  number: 0,
  first: true,
  last: true,
  numberOfElements: 0
}

describe('submission store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('serializes generic submission filters including project and source', async () => {
    api.get.mockResolvedValue({ data: page })
    const store = useSubmissionStore()

    await store.fetchSubmissions({
      projectId: 'p1',
      formId: 'f1',
      status: 'SUBMITTED',
      source: 'ODK Collect',
      page: 2,
      size: 25,
      sort: 'submittedAt,desc'
    })

    const url = api.get.mock.calls[0][0] as string
    expect(url).toContain('/submissions?')
    expect(url).toContain('projectId=p1')
    expect(url).toContain('formId=f1')
    expect(url).toContain('status=SUBMITTED')
    expect(url).toContain('source=ODK+Collect')
    expect(url).toContain('page=2')
    expect(url).toContain('size=25')
  })

  it('updates status and current submission from the API response', async () => {
    const detail = {
      id: 's1',
      instanceId: 'uuid:s1',
      formId: 'f1',
      formTitle: 'Registration',
      formXmlns: 'registration',
      formVersionId: 'fv1',
      formVersion: '1',
      xmlContent: '<data/>',
      jsonContent: '{}',
      status: 'APPROVED',
      metadata: {},
      submittedAt: '2026-08-14T10:00:00Z',
      submittedById: null,
      submittedByName: null,
      reviewedAt: null,
      reviewedById: null,
      reviewNotes: 'ok',
      attachments: [],
      answers: [],
      events: []
    }
    api.put.mockResolvedValue({ data: detail })
    const store = useSubmissionStore()

    const result = await store.updateStatus('s1', { status: 'APPROVED', reviewNotes: 'ok' })

    expect(api.put).toHaveBeenCalledWith('/submissions/s1/status', { status: 'APPROVED', reviewNotes: 'ok' })
    expect(result.status).toBe('APPROVED')
    expect(store.currentSubmission?.status).toBe('APPROVED')
  })
})
