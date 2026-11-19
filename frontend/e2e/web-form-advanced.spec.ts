import { expect, test, type Route } from '@playwright/test'
import { authenticatePage } from './helpers/apiFixtures'
import { fulfillJson } from './helpers/acceptanceFixtures'

function definition(route: Route, title: string) {
  return fulfillJson(route, {
    formVersionId: 'fv-advanced',
    title,
    xmlFormId: 'advanced_e2e',
    availableLocales: ['en'],
    compatibilityWarnings: [],
  })
}

test('web form repeat flow adds JavaRosa repeat instances through browser controls', async ({ page }) => {
  await authenticatePage(page)

  await page.route(
    '**/api/web/forms/fv-advanced/definition',
    route => definition(route, 'Repeat Acceptance'),
  )

  // Start on one existing, user-managed repeat instance. This certifies the
  // browser's real repeat controls without assuming a particular empty-juncture
  // presentation before the first JavaRosa repeat exists.
  await page.route('**/api/web/forms/fv-advanced/sessions', route => fulfillJson(route, {
    sessionId: 'repeat-session',
    formVersionId: 'fv-advanced',
    status: 'IN_PROGRESS',
    currentEvent: 16,
    isAtBeginning: false,
    isAtEnd: false,
    question: null,
    group: {
      label: 'Household Member',
      path: 'household/members/member',
      instancePath: 'household[1]/members[1]/member[1]',
      depth: 1,
      hierarchy: [],
      isRepeat: true,
      repeatCount: 1,
      currentRepeatIndex: 0,
      repeatTargetCount: null,
      repeatCountControlled: false,
      canAddRepeat: true,
      canDeleteRepeat: false,
    },
    repeatJuncture: null,
  }))

  let repeatCount = 1
  await page.route(
    '**/api/web/sessions/repeat-session/repeats/new',
    async route => {
      repeatCount += 1
      await fulfillJson(route, {
        event: 16,
        isAtBeginning: false,
        isAtEnd: false,
        success: true,
        question: null,
        group: {
          label: 'Household Member',
          path: 'household/members/member',
          instancePath: `household[1]/members[1]/member[${repeatCount}]`,
          depth: 1,
          hierarchy: [],
          isRepeat: true,
          repeatCount,
          currentRepeatIndex: repeatCount - 1,
          repeatTargetCount: null,
          repeatCountControlled: false,
          canAddRepeat: true,
          canDeleteRepeat: true,
        },
        repeatJuncture: null,
      })
    },
  )

  await page.goto('/forms/fv-advanced/web')

  await expect(page.getByRole('heading', { name: 'Repeat Acceptance' })).toBeVisible()
  await expect(page.getByText('Entry 1 of 1')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add another' })).toBeVisible()

  const repeatRequest = page.waitForRequest(request =>
    request.url().endsWith('/api/web/sessions/repeat-session/repeats/new')
      && request.method() === 'POST'
  )

  await page.getByRole('button', { name: 'Add another' }).click()
  await repeatRequest

  await expect(page.getByText('Entry 2 of 2')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Previous entry' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Delete entry' })).toBeVisible()
  expect(repeatCount).toBe(2)
})

test('web form media upload sends actual browser file bytes and stores the filename answer', async ({ page }) => {
  await authenticatePage(page)

  const mediaQuestion = {
    index: 1,
    name: 'photo',
    path: '/data/photo',
    instancePath: '/data/photo',
    groupHierarchy: [],
    repeatPath: null,
    repeatIndex: null,
    label: 'Household photo',
    hint: 'Attach a test image',
    dataType: 10,
    controlType: 5,
    required: false,
    readOnly: false,
    constraintText: '',
    appearanceHint: '',
    mediaType: 'image/*',
    currentAnswer: null,
    choices: [],
  }

  await page.route(
    '**/api/web/forms/fv-advanced/definition',
    route => definition(route, 'Media Acceptance'),
  )

  await page.route('**/api/web/forms/fv-advanced/sessions', route => fulfillJson(route, {
    sessionId: 'media-session',
    formVersionId: 'fv-advanced',
    status: 'active',
    currentEvent: 4,
    isAtBeginning: false,
    isAtEnd: false,
    question: mediaQuestion,
    group: null,
    repeatJuncture: null,
  }))

  await page.route(
    '**/api/web/sessions/media-session/attachments',
    route => fulfillJson(route, {
      attachmentId: 'attachment-1',
      fileName: 'house.jpg',
      contentType: 'image/jpeg',
      fileSize: 4,
      storagePath: 'web-form/media-session/house.jpg',
      contentHash: 'e2e-hash',
    }),
  )

  await page.route(
    '**/api/web/sessions/media-session/answers',
    route => fulfillJson(route, {
      answerId: 'answer-photo',
      questionPath: '/data/photo',
      instancePath: '/data/photo',
      questionName: 'photo',
      value: 'house.jpg',
      isRelevant: true,
      isRequired: false,
      validationStatus: 'valid',
      constraintMessage: '',
      currentEvent: 4,
      isAtEnd: false,
      nextQuestion: { ...mediaQuestion, currentAnswer: 'house.jpg' },
      nextGroup: null,
      nextRepeatJuncture: null,
    }),
  )

  await page.goto('/forms/fv-advanced/web')
  await expect(page.getByRole('heading', { name: 'Media Acceptance' })).toBeVisible()

  const attachmentRequest = page.waitForRequest(request =>
    request.url().endsWith('/api/web/sessions/media-session/attachments')
      && request.method() === 'POST'
  )
  const answerRequest = page.waitForRequest(request =>
    request.url().endsWith('/api/web/sessions/media-session/answers')
      && request.method() === 'POST'
  )

  await page.getByLabel('image upload').setInputFiles({
    name: 'house.jpg',
    mimeType: 'image/jpeg',
    buffer: Buffer.from([1, 2, 3, 4]),
  })

  const attachment = await attachmentRequest
  const answer = await answerRequest

  expect(attachment.postDataJSON()).toMatchObject({
    questionPath: '/data/photo',
    fileName: 'house.jpg',
    contentType: 'image/jpeg',
    fileSize: 4,
    content: [1, 2, 3, 4],
  })

  expect(answer.postDataJSON()).toMatchObject({
    questionPath: '/data/photo',
    instancePath: '/data/photo',
    value: 'house.jpg',
  })

  await expect(page.getByText('house.jpg')).toBeVisible()
})
