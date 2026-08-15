import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import api from '@/services/api'
import WebFormView from './WebFormView.vue'

const routerMocks = vi.hoisted(() => ({
  route: {
    params: {
      formVersionId: 'form-version-1',
      sessionId: 'session-1',
    },
  },
  push: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => routerMocks.route,
  useRouter: () => ({ push: routerMocks.push }),
}))

vi.mock('@/services/api', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}))

const nestedQuestion = {
  index: 7,
  name: 'note',
  path: 'members/member/visits/visit/note',
  instancePath: 'members[1]/member[2]/visits[1]/visit[1]/note[1]',
  groupHierarchy: [
    {
      label: 'Member',
      path: 'members/member',
      instancePath: 'members[1]/member[2]',
      repeat: true,
      repeatIndex: 1,
      depth: 0,
    },
    {
      label: 'Visit',
      path: 'members/member/visits/visit',
      instancePath: 'members[1]/member[2]/visits[1]/visit[1]',
      repeat: true,
      repeatIndex: 0,
      depth: 1,
    },
  ],
  repeatPath: 'members/member/visits/visit',
  repeatIndex: 0,
  label: 'Visit note',
  hint: '',
  dataType: 1,
  controlType: 1,
  required: false,
  readOnly: false,
  constraintText: '',
  appearanceHint: '',
  currentAnswer: null,
  choices: [],
}

describe('WebFormView web runtime integration', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    const pinia = createPinia()
    setActivePinia(pinia)

    vi.mocked(api.get).mockImplementation(async (url: string) => {
      if (url === '/web/forms/form-version-1/definition') {
        return {
          data: {
            formVersionId: 'form-version-1',
            title: 'Nested Repeat Form',
            xmlFormId: 'nested_repeat_form',
            availableLocales: ['en'],
            compatibilityWarnings: [
              "External dataset 'people.csv' is referenced by the form but is not available.",
            ],
          },
        } as any
      }

      if (url === '/web/sessions/session-1') {
        return {
          data: {
            id: 'session-1',
            formVersionId: 'form-version-1',
            status: 'active',
            currentScreen: 'question',
            startedAt: '2026-08-14T06:00:00Z',
            lastActivityAt: '2026-08-14T06:00:00Z',
            completedAt: null,
          },
        } as any
      }

      if (url === '/web/sessions/session-1/current') {
        return {
          data: {
            event: 4,
            isAtBeginning: false,
            isAtEnd: false,
            question: nestedQuestion,
            group: null,
            repeatJuncture: null,
          },
        } as any
      }

      throw new Error(`Unexpected GET ${url}`)
    })

    vi.mocked(api.post).mockImplementation(async (url: string, body?: any) => {
      if (url === '/web/sessions/session-1/answers') {
        return {
          data: {
            answerId: 'answer-1',
            questionPath: body.questionPath,
            instancePath: body.instancePath,
            questionName: 'note',
            value: body.value,
            isRelevant: true,
            isRequired: false,
            validationStatus: 'valid',
            constraintMessage: null,
            currentEvent: 4,
            isAtEnd: false,
            nextQuestion: {
              ...nestedQuestion,
              currentAnswer: body.value,
            },
            nextGroup: null,
            nextRepeatJuncture: null,
          },
        } as any
      }

      throw new Error(`Unexpected POST ${url}`)
    })
  })

  it('preserves the complete nested repeat instance path from the rendered question to the answer API', async () => {
    const wrapper = mount(WebFormView, {
      global: {
        plugins: [createPinia()],
      },
    })

    await flushPromises()

    expect(wrapper.text()).toContain('Nested Repeat Form')
    expect(wrapper.text()).toContain('Form compatibility notice')
    expect(wrapper.text()).toContain("External dataset 'people.csv'")
    expect(wrapper.text()).toContain('Member')
    expect(wrapper.text()).toContain('entry 2')
    expect(wrapper.text()).toContain('Visit')
    expect(wrapper.text()).toContain('entry 1')

    const input = wrapper.find('input.form-input')
    expect(input.exists()).toBe(true)

    await input.setValue('Follow-up completed')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith(
      '/web/sessions/session-1/answers',
      {
        questionPath: 'members/member/visits/visit/note',
        instancePath: 'members[1]/member[2]/visits[1]/visit[1]/note[1]',
        value: 'Follow-up completed',
        repeatPath: 'members/member/visits/visit',
        repeatIndex: 0,
      },
    )
  })
})
