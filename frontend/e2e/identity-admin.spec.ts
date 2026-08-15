import { expect, test } from '@playwright/test'
import { authenticatePage } from './helpers/apiFixtures'

test('system admin creates a web user, assigns a global role, and deactivates it', async ({ page }) => {
  await authenticatePage(page)

  const roles = [
    {
      id: 'role-viewer',
      name: 'VIEWER',
      displayName: 'Viewer',
      description: 'Read-only viewer',
      system: true,
      active: true,
    },
    {
      id: 'role-analyst',
      name: 'DATA_ANALYST',
      displayName: 'Data Analyst',
      description: 'Analysis user',
      system: true,
      active: true,
    },
  ]

  const users: any[] = []
  const appUsers: any[] = []

  await page.route('**/api/admin/identity/roles', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(roles) }),
  )

  await page.route('**/api/admin/identity/app-users', route =>
    route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(appUsers) }),
  )

  await page.route('**/api/admin/identity/users', async route => {
    if (route.request().method() === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(users),
      })
      return
    }

    const input = route.request().postDataJSON() as any
    const created = {
      id: 'user-created',
      username: input.username,
      email: input.email,
      firstName: input.firstName || null,
      lastName: input.lastName || null,
      fullName: `${input.firstName || ''} ${input.lastName || ''}`.trim() || input.username,
      phoneNumber: input.phoneNumber || null,
      active: true,
      verified: true,
      roles: input.roleNames || ['VIEWER'],
      lastLoginAt: null,
      createdAt: '2026-08-15T12:00:00Z',
      updatedAt: '2026-08-15T12:00:00Z',
    }
    users.push(created)

    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify(created),
    })
  })

  await page.route('**/api/admin/identity/users/user-created/status', async route => {
    const input = route.request().postDataJSON() as { active: boolean }
    users[0] = { ...users[0], active: input.active }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(users[0]),
    })
  })

  await page.goto('/admin/identity')
  await expect(page.getByRole('heading', { name: 'Identity Administration' })).toBeVisible()

  await page.getByRole('button', { name: 'New web user' }).click()
  await page.getByLabel('Username').fill('playwright.user')
  await page.getByLabel('Email').fill('playwright@example.org')
  await page.getByLabel('Password').fill('secret123')
  await page.getByLabel('First name').fill('Playwright')
  await page.getByLabel('Last name').fill('User')

  const analyst = page.getByLabel(/Data Analyst/)
  await analyst.check()
  const viewer = page.getByLabel(/Viewer/)
  if (await viewer.isChecked()) await viewer.uncheck()

  const createRequest = page.waitForRequest(request =>
    request.url().endsWith('/api/admin/identity/users')
      && request.method() === 'POST',
  )
  await page.getByRole('button', { name: 'Save web user' }).click()
  const request = await createRequest

  expect(request.postDataJSON()).toMatchObject({
    username: 'playwright.user',
    email: 'playwright@example.org',
    roleNames: ['DATA_ANALYST'],
  })
  const createdRow = page.locator('tbody tr').filter({ hasText: 'playwright.user' })
  await expect(createdRow).toBeVisible()
  await expect(createdRow.getByText('DATA_ANALYST', { exact: true })).toBeVisible()

  page.once('dialog', dialog => dialog.accept())
  await createdRow.getByRole('button', { name: 'Deactivate' }).evaluate(
    (button: HTMLButtonElement) => button.click(),
  )

  await expect(createdRow.getByText('Inactive', { exact: true })).toBeVisible()
})
