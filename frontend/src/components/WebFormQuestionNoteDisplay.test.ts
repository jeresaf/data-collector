import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import WebFormQuestion from './WebFormQuestion.vue'
import type { QuestionResponse } from '@/stores/webForm'

function question(overrides: Partial<QuestionResponse> = {}): QuestionResponse {
  return {
    index: 1,
    name: 'welcome',
    path: 'welcome',
    instancePath: 'data[1]/welcome[1]',
    groupHierarchy: [],
    repeatPath: null,
    repeatIndex: null,
    label: 'Farm Data Collection Test Form',
    hint: 'Instructions for the enumerator.',
    questionType: 'text',
    dataType: 1,
    controlType: 1,
    required: false,
    readOnly: true,
    constraintText: '',
    appearanceHint: '',
    mediaType: null,
    currentAnswer: null,
    choices: [],
    ...overrides,
  }
}

function mountQuestion(q: QuestionResponse) {
  return mount(WebFormQuestion, {
    props: { question: q, value: '' },
    global: { plugins: [createPinia()] },
  })
}

describe('WebFormQuestion note rendering', () => {
  it('renders readonly XLSForm note text without a disabled answer input', () => {
    const wrapper = mountQuestion(question())

    expect(wrapper.find('.label-note').exists()).toBe(true)
    expect(wrapper.find('.label-note').text()).toContain('Farm Data Collection Test Form')
    expect(wrapper.find('.label-note').text()).toContain('Instructions for the enumerator.')
    expect(wrapper.find('input.form-input').exists()).toBe(false)
    expect(wrapper.find('textarea.form-textarea').exists()).toBe(false)
  })

  it('renders explicit semantic notes without an answer control', () => {
    const wrapper = mountQuestion(question({
      name: 'summary_note',
      questionType: 'note',
      label: 'Submission summary',
    }))

    expect(wrapper.find('.label-note').exists()).toBe(true)
    expect(wrapper.find('input.form-input').exists()).toBe(false)
  })
})
