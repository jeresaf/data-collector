import { beforeEach, describe, expect, it } from 'vitest'
import router, { authGuard } from './index'
import {
  ACCESS_TOKEN_STORAGE_KEY,
  AUTH_USER_STORAGE_KEY,
} from '@/services/api'

describe('application routes', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('registers login and the existing core application routes', () => {
    const routeNames = new Set(router.getRoutes().map((route) => route.name))

    expect(routeNames.has('login')).toBe(true)
    expect(routeNames.has('projects')).toBe(true)
    expect(routeNames.has('project-detail')).toBe(true)
    expect(routeNames.has('form-detail')).toBe(true)
    expect(routeNames.has('submissions')).toBe(true)
    expect(routeNames.has('submission-detail')).toBe(true)
  })

  it('preserves both new and resumable web-form URLs', () => {
    const newForm = router.resolve({
      name: 'web-form-new',
      params: { formVersionId: 'form-version-1' },
    })
    const resumedForm = router.resolve({
      name: 'web-form-resume',
      params: {
        formVersionId: 'form-version-1',
        sessionId: 'session-1',
      },
    })

    expect(newForm.fullPath).toBe('/forms/form-version-1/web')
    expect(resumedForm.fullPath).toBe(
      '/forms/form-version-1/web/session-1',
    )
  })

  it('resolves the form administration detail route', () => {
    const detail = router.resolve({
      name: 'form-detail',
      params: { projectId: 'project-1', formId: 'form-1' },
    })

    expect(detail.fullPath).toBe('/projects/project-1/forms/form-1')
  })

  it('marks every application route except login as protected', () => {
    const protectedNames = [
      'projects',
      'project-detail',
      'form-detail',
      'submissions',
      'submission-detail',
      'web-form-new',
      'web-form-resume',
    ]

    for (const name of protectedNames) {
      const route = router.getRoutes().find((candidate) => candidate.name === name)
      expect(route?.meta.requiresAuth, `${name} should require auth`).toBe(true)
    }

    const login = router.getRoutes().find((route) => route.name === 'login')
    expect(login?.meta.requiresAuth).not.toBe(true)
  })

  it('redirects unauthenticated users to login with the requested destination', () => {
    const target = router.resolve('/submissions')

    expect(authGuard(target as any)).toEqual({
      name: 'login',
      query: { redirect: '/submissions' },
    })
  })

  it('allows an authenticated session through protected routes', () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, 'access-token')
    localStorage.setItem(
      AUTH_USER_STORAGE_KEY,
      JSON.stringify({
        id: 'user-1',
        username: 'admin',
        email: 'admin@odk-platform.local',
        fullName: 'System Administrator',
        roles: ['SYSTEM_ADMIN'],
        permissions: ['PROJECT_READ'],
      }),
    )

    const target = router.resolve('/submissions')
    expect(authGuard(target as any)).toBe(true)
  })

  it('enforces optional role and permission metadata when a route declares it', () => {
    localStorage.setItem(ACCESS_TOKEN_STORAGE_KEY, 'access-token')
    localStorage.setItem(
      AUTH_USER_STORAGE_KEY,
      JSON.stringify({
        id: 'user-1',
        username: 'viewer',
        email: 'viewer@example.com',
        fullName: 'Viewer',
        roles: ['VIEWER'],
        permissions: ['PROJECT_READ'],
      }),
    )

    const roleRestricted = {
      ...router.resolve('/submissions'),
      meta: {
        requiresAuth: true,
        roles: ['SYSTEM_ADMIN'],
      },
    }
    const permissionRestricted = {
      ...router.resolve('/submissions'),
      meta: {
        requiresAuth: true,
        permissions: ['SUBMISSION_DELETE'],
      },
    }

    expect(authGuard(roleRestricted as any)).toEqual({ name: 'projects' })
    expect(authGuard(permissionRestricted as any)).toEqual({ name: 'projects' })
  })
})
