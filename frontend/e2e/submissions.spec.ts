import { expect, test } from '@playwright/test'
import { mockProjectApis, mockSubmissionList } from './helpers/apiFixtures'

test.beforeEach(async ({ page }) => {
  await mockProjectApis(page)
  await mockSubmissionList(page, true)
})

test('submission list opens submission detail', async ({ page }) => {
  await page.goto('/submissions')

  await expect(page.getByRole('heading', { name: 'Submissions', exact: true })).toBeVisible()
  await expect(page.getByText('Registration')).toBeVisible()
  await expect(page.getByText('ODK Collect')).toBeVisible()
  await expect(page.getByText('SUBMITTED', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'View submission details' }).click()
  await expect(page).toHaveURL(/\/submissions\/submission-1$/)
  await expect(page.getByRole('heading', { name: 'Registration' })).toBeVisible()
  await expect(page.getByText('uuid:11111111-1111-1111-1111-111111111111')).toBeVisible()
  await expect(page.getByLabel('XML', { exact: true })).toBeVisible()
  await expect(page.getByLabel('JSON', { exact: true })).toBeVisible()
})
