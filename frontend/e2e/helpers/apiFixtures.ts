import type { Page, Route } from '@playwright/test'

const ACCESS_TOKEN_STORAGE_KEY = 'odk_access_token'
const REFRESH_TOKEN_STORAGE_KEY = 'odk_refresh_token'
const AUTH_USER_STORAGE_KEY = 'odk_auth_user'

export const e2eUser = {
  id: 'e2e-user',
  username: 'e2e.admin',
  email: 'e2e@example.test',
  fullName: 'E2E Administrator',
  roles: ['SYSTEM_ADMIN'],
  permissions: [
    'PROJECT_READ',
    'PROJECT_UPDATE',
    'FORM_READ',
    'FORM_UPDATE',
    'SUBMISSION_READ',
    'SUBMISSION_UPDATE',
  ],
}

export const projectFixture = {
  id: 'project-1',
  name: 'Compatibility Certification',
  description: 'Generic ODK compatibility project',
  isActive: true,
  createdAt: '2026-08-14T08:00:00Z',
  updatedAt: '2026-08-14T09:00:00Z'
}

export const submissionFixture = {
  id: 'submission-1',
  instanceId: 'uuid:11111111-1111-1111-1111-111111111111',
  formId: 'form-1',
  formTitle: 'Registration',
  formXmlns: 'registration',
  formVersionId: 'fv-1',
  formVersion: '1',
  status: 'SUBMITTED',
  source: 'ODK Collect',
  submittedAt: '2026-08-14T10:00:00Z',
  submittedById: 'app-user-1',
  submittedByName: 'Field Tester',
  reviewedAt: null,
  reviewedById: null,
  attachmentCount: 0
}

export const submissionDetailFixture = {
  ...submissionFixture,
  xmlContent: '<data id="registration"/>',
  jsonContent: '{"name":"Amina"}',
  metadata: { sourceClient: 'ODK Collect' },
  reviewNotes: null,
  attachments: [],
  answers: [
    {
      id: 'answer-1',
      questionName: 'name',
      questionPath: '/data/name',
      value: 'Amina',
      repeatIndex: null
    }
  ],
  events: []
}

export const emptySubmissionPage = {
  content: [],
  totalElements: 0,
  totalPages: 0,
  size: 20,
  number: 0,
  first: true,
  last: true,
  numberOfElements: 0
}

export const submissionPage = {
  ...emptySubmissionPage,
  content: [submissionFixture],
  totalElements: 1,
  totalPages: 1,
  numberOfElements: 1
}

async function fulfillJson(route: Route, body: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body)
  })
}

/**
 * Seed the same browser auth contract used by the production auth store/router.
 *
 * addInitScript runs before the Vue application starts, so protected routes see
 * an authenticated user on their very first navigation. /auth/me is mocked as
 * well because App.vue restores/verifies the stored session during startup.
 */
export async function authenticatePage(page: Page) {
  await page.addInitScript(
    ({ accessTokenKey, refreshTokenKey, userKey, user }) => {
      localStorage.setItem(accessTokenKey, 'e2e-access-token')
      localStorage.setItem(refreshTokenKey, 'e2e-refresh-token')
      localStorage.setItem(userKey, JSON.stringify(user))
    },
    {
      accessTokenKey: ACCESS_TOKEN_STORAGE_KEY,
      refreshTokenKey: REFRESH_TOKEN_STORAGE_KEY,
      userKey: AUTH_USER_STORAGE_KEY,
      user: e2eUser,
    }
  )

  await page.route('**/api/auth/me', async route => {
    await fulfillJson(route, e2eUser)
  })

  await page.route('**/api/auth/logout', async route => {
    await route.fulfill({ status: 200, body: '' })
  })
}

export async function mockProjectApis(page: Page) {
  // Every current project/submission/web-form E2E flow uses this shared setup.
  // Authenticate here so route protection is exercised instead of bypassed.
  await authenticatePage(page)

  await page.route('**/api/projects', async route => {
    if (route.request().method() === 'GET') {
      await fulfillJson(route, [projectFixture])
      return
    }
    await route.continue()
  })

  await page.route('**/api/projects/project-1', async route => {
    await fulfillJson(route, projectFixture)
  })

  await page.route('**/api/projects/project-1/forms', async route => {
    await fulfillJson(route, [])
  })
}

export async function mockSubmissionList(page: Page, withSubmission = true) {
  await page.route(/\/api\/submissions\?.*/, async route => {
    await fulfillJson(route, withSubmission ? submissionPage : emptySubmissionPage)
  })

  await page.route('**/api/submissions/submission-1', async route => {
    await fulfillJson(route, submissionDetailFixture)
  })
}
