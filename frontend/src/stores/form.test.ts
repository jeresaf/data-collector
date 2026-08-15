import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useFormStore } from './form'

const api = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  delete: vi.fn()
}))

vi.mock('@/services/api', () => ({ default: api }))

describe('form upload store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    api.get.mockResolvedValue({ data: [] })
  })

  it('uploads XLSForm to the conversion endpoint and preserves warnings', async () => {
    api.post.mockResolvedValue({
      data: {
        success: true,
        message: 'converted',
        formId: 'form-db-id',
        xmlns: 'people',
        version: '1',
        warnings: ['custom column preserved'],
        extraColumns: { survey: ['custom'] }
      }
    })
    const store = useFormStore()
    const data = new FormData()
    data.append('file', new File(['xlsx'], 'people.xlsx'))

    const result = await store.uploadXlsForm('project-1', data)

    expect(api.post).toHaveBeenCalledWith(
      '/projects/project-1/forms/upload-xlsform',
      data,
      expect.objectContaining({ headers: { 'Content-Type': 'multipart/form-data' } })
    )
    expect(result.warnings).toEqual(['custom column preserved'])
    expect(result.extraColumns).toEqual({ survey: ['custom'] })
  })

  it('uses the actual XML upload endpoint rather than the removed upload-xml path', async () => {
    api.post.mockResolvedValue({ data: { id: 'f1', title: 'XML', xmlns: 'xml', version: '1', md5Hash: 'abc' } })
    const store = useFormStore()
    const data = new FormData()

    const result = await store.uploadXmlForm('project-2', data)

    expect(api.post).toHaveBeenCalledWith(
      '/projects/project-2/forms/upload',
      data,
      expect.any(Object)
    )
    expect(result.success).toBe(true)
    expect(result.formId).toBe('f1')
  })
})
