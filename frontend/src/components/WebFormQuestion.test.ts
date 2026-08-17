import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import WebFormQuestion from './WebFormQuestion.vue'
import {
  CONTROL_UPLOAD,
  DATATYPE_BINARY,
  DATATYPE_BARCODE,
  type QuestionResponse,
} from '@/stores/webForm'

function uploadQuestion(overrides: Partial<QuestionResponse> = {}): QuestionResponse {
  return {
    index: 1,
    name: 'attachment',
    path: 'attachment',
    instancePath: 'data[1]/attachment[1]',
    groupHierarchy: [],
    repeatPath: null,
    repeatIndex: null,
    label: 'Attachment',
    hint: '',
    dataType: DATATYPE_BINARY,
    controlType: CONTROL_UPLOAD,
    required: false,
    readOnly: false,
    constraintText: '',
    appearanceHint: '',
    mediaType: null,
    currentAnswer: null,
    choices: [],
    ...overrides,
  }
}

function mountQuestion(question: QuestionResponse) {
  return mount(WebFormQuestion, {
    props: {
      question,
      value: '',
    },
    global: {
      plugins: [createPinia()],
    },
  })
}

describe('WebFormQuestion media uploads', () => {
  it.each([
    ['image/*', 'image'],
    ['audio/*', 'audio'],
    ['video/*', 'video'],
    ['application/pdf', 'file'],
  ])('uses XForm mediatype %s for the browser picker', (mediaType, kind) => {
    const wrapper = mountQuestion(uploadQuestion({ mediaType }))
    const input = wrapper.get('input[type="file"]')

    expect(input.attributes('accept')).toBe(mediaType)
    expect(input.attributes('aria-label')).toBe(`${kind} upload`)
  })

  it('falls back to the existing broad picker when mediatype is absent', () => {
    const wrapper = mountQuestion(uploadQuestion())
    const input = wrapper.get('input[type="file"]')

    expect(input.attributes('accept')).toBe('image/*,audio/*,video/*,application/pdf')
    expect(input.attributes('aria-label')).toBe('file upload')
  })
})

describe('WebFormQuestion signature appearance', () => {
  it('renders the signature canvas instead of a generic file picker', () => {
    const wrapper = mountQuestion(uploadQuestion({
      name: 'signature',
      path: 'signature',
      appearanceHint: 'signature',
      mediaType: 'image/*',
      label: 'Signature',
    }))

    expect(wrapper.find('canvas[aria-label="Signature drawing area"]').exists()).toBe(true)
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
  })
})

describe('WebFormQuestion barcode control', () => {
  it('renders the camera-capable barcode control for barcode data', () => {
    const wrapper = mountQuestion(uploadQuestion({
      name: 'barcode',
      path: 'barcode',
      label: 'Asset barcode',
      dataType: DATATYPE_BARCODE,
      controlType: 1,
      mediaType: null,
      appearanceHint: '',
    }))

    expect(wrapper.find('input[aria-label="Barcode value"]').exists()).toBe(true)
    expect(wrapper.find('button').text()).toContain('Scan with camera')
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
  })
})

describe('WebFormQuestion Collect-style appearances', () => {
  it('renders ordinary select-one questions as visible radio choices', () => {
    const wrapper = mountQuestion(uploadQuestion({
      name: 'gender',
      path: 'gender',
      instancePath: 'gender[1]',
      label: 'Gender',
      questionType: 'select1',
      dataType: 1,
      controlType: 2,
      mediaType: null,
      appearanceHint: '',
      choices: [
        { value: 'female', label: 'Female' },
        { value: 'male', label: 'Male' },
      ],
    }))

    expect(wrapper.findAll('input[type="radio"]')).toHaveLength(2)
    expect(wrapper.find('select').exists()).toBe(false)
  })

  it('keeps appearance=minimal select-one questions compact', () => {
    const wrapper = mountQuestion(uploadQuestion({
      name: 'district',
      path: 'district',
      instancePath: 'district[1]',
      label: 'District',
      questionType: 'select1',
      dataType: 1,
      controlType: 2,
      mediaType: null,
      appearanceHint: 'minimal',
      choices: [
        { value: 'wakiso', label: 'Wakiso' },
        { value: 'mukono', label: 'Mukono' },
      ],
    }))

    expect(wrapper.find('select').exists()).toBe(true)
    expect(wrapper.findAll('input[type="radio"]')).toHaveLength(0)
  })

  it('renders text appearance=multiline as a textarea', () => {
    const wrapper = mountQuestion(uploadQuestion({
      name: 'notes',
      path: 'notes',
      instancePath: 'notes[1]',
      label: 'Notes',
      questionType: 'text',
      dataType: 1,
      controlType: 1,
      mediaType: null,
      appearanceHint: 'multiline',
    }))

    expect(wrapper.find('textarea.form-textarea').exists()).toBe(true)
    expect(wrapper.find('input.form-input').exists()).toBe(false)
  })
})
