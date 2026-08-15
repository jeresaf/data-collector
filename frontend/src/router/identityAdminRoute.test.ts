import { describe, expect, it } from 'vitest'
import router from './index'

describe('identity administration route', () => {
  it('is protected and restricted to SYSTEM_ADMIN', () => {
    const route = router.getRoutes().find(item => item.name === 'identity-admin')

    expect(route?.path).toBe('/admin/identity')
    expect(route?.meta.requiresAuth).toBe(true)
    expect(route?.meta.roles).toEqual(['SYSTEM_ADMIN'])
    expect(router.resolve({ name: 'identity-admin' }).fullPath).toBe('/admin/identity')
  })
})
