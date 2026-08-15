import { expect, test } from '@playwright/test'
import { mockProjectApis, mockSubmissionList } from './helpers/apiFixtures'

test.beforeEach(async ({ page }) => {
  await mockProjectApis(page)
  await mockSubmissionList(page, false)
})

test('projects are reachable and project detail keeps submissions project-scoped', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByRole('heading', { name: 'Projects', exact: true })).toBeVisible()
  await expect(page.getByText('Compatibility Certification')).toBeVisible()

  await page.getByRole('link', { name: 'Open Compatibility Certification' }).click()
  await expect(page).toHaveURL(/\/projects\/project-1$/)
  await expect(page.getByRole('heading', { name: 'Compatibility Certification' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Forms' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Reference Data' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Reports' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Audit' })).toBeVisible()

  const submissionRequest = page.waitForRequest(request =>
    request.url().includes('/api/submissions?') && request.url().includes('projectId=project-1')
  )
  await page.getByRole('button', { name: 'Submissions' }).click()
  await submissionRequest
  await expect(page.getByText('No submissions found.')).toBeVisible()
})
