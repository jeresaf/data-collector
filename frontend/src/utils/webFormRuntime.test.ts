import { describe, expect, it } from 'vitest'
import type { QuestionResponse } from '@/stores/webForm'
import { buildSaveAnswerRequest } from './webFormRuntime'

function question(overrides: Partial<QuestionResponse> = {}): QuestionResponse {
  return {
    index: 1,
    name: 'name',
    path: 'name',
    instancePath: 'name[1]',
    groupHierarchy: [],
    repeatPath: null,
    repeatIndex: null,
    label: 'Name',
    hint: '',
    dataType: 1,
    controlType: 1,
    required: false,
    readOnly: false,
    constraintText: '',
    appearanceHint: '',
    currentAnswer: null,
    choices: [],
    ...overrides,
  }
}

describe('buildSaveAnswerRequest', () => {
  it('uses the canonical question path for grouped questions', () => {
    const request = buildSaveAnswerRequest(
      question({
        name: 'name',
        path: 'household/name',
      }),
      'Smith Family',
    )

    expect(request).toEqual({
      questionPath: 'household/name',
      instancePath: 'name[1]',
      value: 'Smith Family',
      repeatPath: undefined,
      repeatIndex: undefined,
    })
  })

  it('preserves repeat path and zero-based repeat multiplicity', () => {
    const request = buildSaveAnswerRequest(
      question({
        name: 'name',
        path: 'household/members/member/name',
        instancePath: 'household[1]/members[1]/member[2]/name[1]',
        repeatPath: 'household/members/member',
        repeatIndex: 1,
      }),
      'Member 2',
    )

    expect(request).toEqual({
      questionPath: 'household/members/member/name',
      instancePath: 'household[1]/members[1]/member[2]/name[1]',
      value: 'Member 2',
      repeatPath: 'household/members/member',
      repeatIndex: 1,
    })
  })

  it('keeps nested repeat instances distinct even when the inner repeat index is the same', () => {
    const firstOuter = buildSaveAnswerRequest(
      question({
        path: 'members/visits/date',
        instancePath: 'members[1]/visits[1]/date[1]',
        repeatPath: 'members/visits',
        repeatIndex: 0,
      }),
      '2026-08-01',
    )

    const secondOuter = buildSaveAnswerRequest(
      question({
        path: 'members/visits/date',
        instancePath: 'members[2]/visits[1]/date[1]',
        repeatPath: 'members/visits',
        repeatIndex: 0,
      }),
      '2026-08-02',
    )

    expect(firstOuter.repeatIndex).toBe(secondOuter.repeatIndex)
    expect(firstOuter.instancePath).not.toBe(secondOuter.instancePath)
  })

  it('falls back to the leaf name only when the backend path is empty', () => {
    const request = buildSaveAnswerRequest(question({ path: '' }), 'Alice')

    expect(request.questionPath).toBe('name')
  })
})
