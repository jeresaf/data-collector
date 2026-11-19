import { expect, test, type Route } from '@playwright/test'
import { emptySubmissionPage, mockProjectApis } from './helpers/apiFixtures'

const question = {
  index: 1,
  name: 'name',
  path: '/data/name',
  instancePath: '/data/name',
  groupHierarchy: [],
  repeatPath: null,
  repeatIndex: null,
  label: 'Respondent name',
  hint: 'Enter a name',
  dataType: 1,
  controlType: 1,
  required: true,
  readOnly: false,
  constraintText: '',
  appearanceHint: '',
  currentAnswer: null,
  choices: []
}

async function json(route: Route, body: unknown) {
  await route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) })
}

test('mobile-first web form can answer, finish and submit', async ({ page }) => {
  await mockProjectApis(page)

  await page.route('**/api/web/forms/fv-1/definition', route => json(route, {
    formVersionId: 'fv-1',
    title: 'Web Registration',
    xmlFormId: 'registration',
    availableLocales: ['en'],
    compatibilityWarnings: []
  }))

  await page.route('**/api/web/forms/fv-1/sessions', route => json(route, {
    sessionId: 'session-1',
    formVersionId: 'fv-1',
    status: 'IN_PROGRESS',
    currentEvent: 4,
    isAtBeginning: false,
    isAtEnd: false,
    question,
    group: null,
    repeatJuncture: null
  }))

  await page.route('**/api/web/sessions/session-1/answers', route => json(route, {
    answerId: 'answer-1',
    questionPath: '/data/name',
    instancePath: '/data/name',
    questionName: 'name',
    value: 'Amina',
    isRelevant: true,
    isRequired: true,
    validationStatus: 'valid',
    constraintMessage: '',
    currentEvent: 4,
    isAtEnd: false,
    nextQuestion: { ...question, currentAnswer: 'Amina' },
    nextGroup: null,
    nextRepeatJuncture: null
  }))

  await page.route('**/api/web/sessions/session-1/navigate/next', route => json(route, {
    event: 1,
    isAtBeginning: false,
    isAtEnd: true,
    success: true,
    question: null,
    group: null,
    repeatJuncture: null
  }))

  // Submission requires successful server validation before the confirmation opens.
  // The production UI now validates both at the initial submit click and at confirmation.
  await page.route('**/api/web/sessions/session-1/validate', route => json(route, {
    valid: true,
    message: 'All answers are valid',
    failedQuestion: null
  }))

  await page.route('**/api/web/sessions/session-1/finalize', route => json(route, {
    success: true,
    submissionId: 'submission-web-1',
    instanceId: 'uuid:web-1',
    message: 'Submitted'
  }))

  await page.route(/\/api\/submissions\?.*/, route => json(route, emptySubmissionPage))

  page.on('dialog', dialog => dialog.accept())
  await page.goto('/forms/fv-1/web')

  await expect(page.getByRole('heading', { name: 'Web Registration' })).toBeVisible()
  await expect(page.getByText('Respondent name')).toBeVisible()
  await page.locator('input[type="text"]').fill('Amina')
  await page.getByRole('button', { name: 'Next →' }).click()

  await expect(page.getByRole('heading', { name: 'Form Complete' })).toBeVisible()
  await page.getByRole('button', { name: 'Submit Form' }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('dialog').getByRole('button', { name: 'Submit' }).click()

  await expect(page).toHaveURL(/\/submissions$/)
  await expect(page.getByRole('heading', { name: 'Submissions', exact: true })).toBeVisible()
})
