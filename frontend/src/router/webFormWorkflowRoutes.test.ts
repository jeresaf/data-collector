import { describe, expect, it } from 'vitest'
import router from './index'

describe('user-facing web form workflow routes', () => {
  it('registers the required app workflow URLs and protects all of them', () => {
    const expected = [
      ['app-forms', '/app/forms'],
      ['app-form-start', '/app/forms/form-1/start'],
      ['app-session-fill', '/app/sessions/session-1/fill'],
      ['app-session-review', '/app/sessions/session-1/review'],
      ['app-session-submit', '/app/sessions/session-1/submit'],
      ['app-drafts', '/app/drafts'],
      ['app-submitted', '/app/submitted'],
    ] as const

    for (const [name, fullPath] of expected) {
      const params = name === 'app-form-start'
        ? { formId: 'form-1' }
        : name.startsWith('app-session-')
          ? { sessionId: 'session-1' }
          : undefined

      const resolved = router.resolve({ name, params })
      expect(resolved.fullPath).toBe(fullPath)

      const route = router.getRoutes().find(candidate => candidate.name === name)
      expect(route?.meta.requiresAuth).toBe(true)
    }
  })

  it('preserves the existing technical web-form routes', () => {
    expect(router.resolve({
      name: 'web-form-new',
      params: { formVersionId: 'version-1' },
    }).fullPath).toBe('/forms/version-1/web')

    expect(router.resolve({
      name: 'web-form-resume',
      params: { formVersionId: 'version-1', sessionId: 'session-1' },
    }).fullPath).toBe('/forms/version-1/web/session-1')
  })
})
