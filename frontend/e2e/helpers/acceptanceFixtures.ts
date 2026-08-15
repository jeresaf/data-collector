import type { Page, Route } from '@playwright/test'
import {
  authenticatePage,
  emptySubmissionPage,
  projectFixture,
} from './apiFixtures'

export const formFixture = {
  id: 'form-1',
  name: 'E2E Registration',
  version: '1',
  description: 'Form used by Playwright acceptance coverage',
  isPublished: true,
  createdAt: '2026-08-15T08:00:00Z',
  updatedAt: '2026-08-15T08:00:00Z',
  formVersionId: 'fv-1',
}

export async function fulfillJson(
  route: Route,
  body: unknown,
  status = 200,
) {
  await route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  })
}

export interface WorkspaceOptions {
  forms?: Array<typeof formFixture>
  projects?: Array<Record<string, unknown>>
}

/**
 * Browser-boundary API fixture used only by the missing acceptance scenarios.
 *
 * It deliberately uses the production auth localStorage contract through
 * authenticatePage(), then mocks API responses while leaving Vue/router/store
 * behavior real.
 */
export async function mockAcceptanceWorkspace(
  page: Page,
  options: WorkspaceOptions = {},
) {
  await authenticatePage(page)

  const state = {
    projects: [...(options.projects ?? [projectFixture])],
    forms: [...(options.forms ?? [])],
  }

  await page.route('**/api/projects', async route => {
    const method = route.request().method()

    if (method === 'GET') {
      await fulfillJson(route, state.projects)
      return
    }

    if (method === 'POST') {
      const input = route.request().postDataJSON() as {
        name?: string
        description?: string
      }

      const created = {
        id: 'project-created',
        name: input.name || 'Created Project',
        description: input.description || '',
        isActive: true,
        createdAt: '2026-08-15T11:00:00Z',
        updatedAt: '2026-08-15T11:00:00Z',
      }

      state.projects.push(created)
      await fulfillJson(route, created)
      return
    }

    await route.fulfill({ status: 405, body: '' })
  })

  await page.route('**/api/projects/project-1', async route => {
    await fulfillJson(route, projectFixture)
  })

  await page.route('**/api/projects/project-1/forms', async route => {
    if (route.request().method() === 'GET') {
      await fulfillJson(route, state.forms)
      return
    }
    await route.fulfill({ status: 405, body: '' })
  })

  await page.route(/\/api\/submissions\?.*/, async route => {
    await fulfillJson(route, emptySubmissionPage)
  })

  return state
}
