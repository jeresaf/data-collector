import { expect, test } from '@playwright/test'
import {
  formFixture,
  fulfillJson,
  mockAcceptanceWorkspace,
} from './helpers/acceptanceFixtures'

const XFORM_XML = `<?xml version="1.0" encoding="UTF-8"?>
<h:html xmlns:h="http://www.w3.org/1999/xhtml"
        xmlns="http://www.w3.org/2002/xforms">
  <h:head>
    <h:title>E2E Registration</h:title>
    <model>
      <instance>
        <data id="e2e_registration" version="1">
          <name/>
        </data>
      </instance>
      <bind nodeset="/data/name" type="string"/>
    </model>
  </h:head>
  <h:body>
    <input ref="/data/name">
      <label>Name</label>
    </input>
  </h:body>
</h:html>`

test('protected app route redirects an unauthenticated browser to login', async ({ page }) => {
  await page.goto('/app/forms')

  await expect.poll(() => new URL(page.url()).pathname).toBe('/login')
  expect(new URL(page.url()).searchParams.get('redirect')).toBe('/app/forms')
  await expect(page.locator('#username')).toBeVisible()
  await expect(page.locator('#password')).toBeVisible()
})

test('admin creates a project from the projects page', async ({ page }) => {
  await mockAcceptanceWorkspace(page)

  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Projects', exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'New Project' }).click()
  await expect(page.getByRole('heading', { name: 'Create Project' })).toBeVisible()

  await page.locator('#name').fill('Playwright Field Project')
  await page.locator('#description').fill('Created through the browser acceptance flow.')

  const createRequest = page.waitForRequest(request =>
    request.url().endsWith('/api/projects') && request.method() === 'POST'
  )

  // Submit the real Vue form without relying on mobile pointer hit-testing
  // inside the animated modal overlay.
  await page.locator('.modal-form').evaluate(
    (form: HTMLFormElement) => form.requestSubmit(),
  )

  const request = await createRequest
  expect(request.postDataJSON()).toMatchObject({
    name: 'Playwright Field Project',
    description: 'Created through the browser acceptance flow.',
  })

  await expect(page.getByText('Playwright Field Project')).toBeVisible()
})

test('admin uploads XForm XML and sees extracted metadata', async ({ page }) => {
  const state = await mockAcceptanceWorkspace(page)

  await page.route('**/api/projects/project-1/forms/upload', async route => {
    expect(route.request().method()).toBe('POST')
    expect(route.request().headers()['content-type']).toContain('multipart/form-data')
    expect(route.request().postData()).toContain('e2e-registration.xml')

    state.forms.splice(0, state.forms.length, {
      ...formFixture,
      id: 'form-uploaded',
      formVersionId: 'fv-uploaded',
    })

    await fulfillJson(route, {
      id: 'form-uploaded',
      title: 'E2E Registration',
      xmlns: 'e2e_registration',
      version: '1',
      md5Hash: '0123456789abcdef0123456789abcdef',
      formVersionId: 'fv-uploaded',
    })
  })

  await page.goto('/projects/project-1')
  await expect(page.getByRole('heading', { name: 'Compatibility Certification' })).toBeVisible()

  await page.getByRole('button', { name: 'Upload Form' }).click()
  await expect(page.getByRole('heading', { name: 'Upload Form' })).toBeVisible()

  await page.getByRole('button', { name: 'XForm XML' }).click()
  await page.locator('#form-file').setInputFiles({
    name: 'e2e-registration.xml',
    mimeType: 'application/xml',
    buffer: Buffer.from(XFORM_XML),
  })

  const uploadRequest = page.waitForRequest(request =>
    request.url().endsWith('/api/projects/project-1/forms/upload')
      && request.method() === 'POST'
  )

  // requestSubmit() exercises the same @submit.prevent handler as the button,
  // while avoiding mobile-emulation hit-test flakiness inside the scrollable modal.
  await page.locator('.modal-form').evaluate(
    (form: HTMLFormElement) => form.requestSubmit(),
  )
  await uploadRequest

  const successHeading = page.getByRole('heading', {
    name: 'Form Uploaded Successfully',
  })
  await expect(successHeading).toBeVisible()

  const successPanel = successHeading.locator('..')
  await expect(successPanel.getByText('E2E Registration', { exact: true })).toBeVisible()
  await expect(successPanel.getByText('e2e_registration', { exact: true })).toBeVisible()
  await expect(
    successPanel.getByText('0123456789abcdef0123456789abcdef', { exact: true }),
  ).toBeVisible()

  await page.getByRole('button', { name: 'Done' }).evaluate(
    (button: HTMLButtonElement) => button.click(),
  )
  await expect(
    page.locator('.forms-grid').getByText('E2E Registration', { exact: true }),
  ).toBeVisible()
})

test('admin builds, runs and exports a generic report', async ({ page }) => {
  await mockAcceptanceWorkspace(page, { forms: [formFixture] })

  const reportDefinition = {
    id: 'report-1',
    projectId: 'project-1',
    formId: 'form-1',
    name: 'E2E Totals',
    description: '',
    reportType: 'TABLE',
    active: true,
    fields: [
      {
        id: 'field-1',
        fieldPath: 'amount',
        fieldKind: 'ANSWER',
        label: 'Amount',
        dataType: 'decimal',
        aggregation: 'SUM',
        groupBy: false,
        sortOrder: 0,
      },
    ],
    filters: [],
    createdAt: '2026-08-15T11:00:00Z',
    updatedAt: '2026-08-15T11:00:00Z',
  }

  await page.route('**/api/projects/project-1/reports', async route => {
    if (route.request().method() === 'GET') {
      await fulfillJson(route, [])
      return
    }

    if (route.request().method() === 'POST') {
      const input = route.request().postDataJSON() as Record<string, unknown>
      await fulfillJson(route, { ...reportDefinition, ...input, id: 'report-1' })
      return
    }

    await route.fulfill({ status: 405, body: '' })
  })

  await page.route(
    '**/api/projects/project-1/reports/forms/form-1/fields',
    route => fulfillJson(route, [
      {
        fieldPath: 'amount',
        label: 'Amount',
        fieldKind: 'ANSWER',
        dataType: 'decimal',
        media: false,
        calculated: false,
      },
    ]),
  )

  await page.route(
    '**/api/projects/project-1/reports/report-1/run',
    route => fulfillJson(route, {
      runId: 'run-1',
      reportId: 'report-1',
      reportName: 'E2E Totals',
      columns: [
        {
          key: 'amount::SUM',
          label: 'SUM Amount',
          dataType: 'decimal',
          fieldKind: 'ANSWER',
          aggregation: 'SUM',
          groupBy: false,
        },
      ],
      rows: [{ 'amount::SUM': 30 }],
      rowCount: 1,
      sourceSubmissionCount: 2,
      executedAt: '2026-08-15T11:05:00Z',
    }),
  )

  await page.route(
    '**/api/projects/project-1/reports/report-1/export/csv',
    async route => {
      await route.fulfill({
        status: 200,
        headers: {
          'content-type': 'text/csv; charset=utf-8',
          'content-disposition': 'attachment; filename="e2e-totals.csv"',
        },
        body: 'SUM Amount\n30\n',
      })
    },
  )

  await page.goto('/projects/project-1')
  await page.getByRole('button', { name: 'Reports' }).click()

  await expect(page.getByRole('heading', { name: 'Generic Reports' })).toBeVisible()

  await page.getByTestId('report-name').fill('E2E Totals')
  await page.getByTestId('source-form').selectOption('form-1')

  const amountRow = page.locator('.field-table tbody tr').filter({ hasText: 'Amount' })
  const amountCheckbox = amountRow.locator('input[type="checkbox"]').first()
  await amountCheckbox.evaluate((checkbox: HTMLInputElement) => checkbox.click())
  await expect(amountCheckbox).toBeChecked()
  await amountRow.locator('select').selectOption('SUM')

  const runRequest = page.waitForRequest(request =>
    request.url().endsWith('/api/projects/project-1/reports/report-1/run')
      && request.method() === 'POST'
  )
  await page.getByRole('button', { name: 'Run Report' }).evaluate(
    (button: HTMLButtonElement) => button.click(),
  )
  await runRequest

  await expect(page.getByRole('heading', { name: 'E2E Totals' })).toBeVisible()
  await expect(page.getByText('1 result rows from 2 matching submissions')).toBeVisible()
  await expect(page.getByRole('cell', { name: '30', exact: true })).toBeVisible()

  const exportRequest = page.waitForRequest(request =>
    request.url().endsWith('/api/projects/project-1/reports/report-1/export/csv')
      && request.method() === 'POST'
  )
  await page.getByRole('button', { name: 'Export CSV' }).evaluate(
    (button: HTMLButtonElement) => button.click(),
  )
  const request = await exportRequest

  expect(request.postDataJSON()).toMatchObject({})
})
