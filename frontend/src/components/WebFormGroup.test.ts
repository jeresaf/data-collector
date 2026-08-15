import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import WebFormGroup from './WebFormGroup.vue'
import {
  EVENT_PROMPT_NEW_REPEAT,
  EVENT_REPEAT,
  type GroupResponse,
  type RepeatJunctureResponse,
} from '@/stores/webForm'

function repeatGroup(overrides: Partial<GroupResponse> = {}): GroupResponse {
  return {
    label: 'Household Member',
    path: 'household/members/member',
    instancePath: 'household[1]/members[1]/member[1]',
    depth: 1,
    hierarchy: [],
    isRepeat: true,
    repeatCount: 2,
    currentRepeatIndex: 0,
    repeatTargetCount: null,
    repeatCountControlled: false,
    canAddRepeat: true,
    canDeleteRepeat: true,
    ...overrides,
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('WebFormGroup repeat controls', () => {
  it('does not offer add/delete actions for repeat_count-controlled repeats', () => {
    const wrapper = mount(WebFormGroup, {
      props: {
        event: EVENT_REPEAT,
        group: repeatGroup({
          repeatCount: 2,
          currentRepeatIndex: 0,
          repeatTargetCount: 2,
          repeatCountControlled: true,
          canAddRepeat: false,
          canDeleteRepeat: false,
        }),
      },
    })

    expect(wrapper.text()).toContain('Instance 1 of 2')
    expect(wrapper.text()).toContain('controlled by the form definition')
    expect(wrapper.text()).not.toContain('Add Another')
    expect(wrapper.text()).not.toContain('Delete This')
  })

  it('emits navigation using the backend repeat multiplicities', async () => {
    const wrapper = mount(WebFormGroup, {
      props: {
        event: EVENT_REPEAT,
        group: repeatGroup({
          repeatCount: 3,
          currentRepeatIndex: 1,
        }),
      },
    })

    const buttons = wrapper.findAll('button')
    await buttons.find((button) => button.text() === 'Previous')!.trigger('click')
    await buttons.find((button) => button.text() === 'Next')!.trigger('click')

    expect(wrapper.emitted('descend')).toEqual([[0], [2]])
  })

  it('allows user-managed repeats to be added and deleted', async () => {
    vi.stubGlobal('confirm', vi.fn(() => true))

    const wrapper = mount(WebFormGroup, {
      props: {
        event: EVENT_REPEAT,
        group: repeatGroup({ currentRepeatIndex: 1 }),
      },
    })

    const buttons = wrapper.findAll('button')
    await buttons.find((button) => button.text() === 'Add Another')!.trigger('click')
    await buttons.find((button) => button.text() === 'Delete This')!.trigger('click')

    expect(wrapper.emitted('new-repeat')).toHaveLength(1)
    expect(wrapper.emitted('delete-repeat')).toHaveLength(1)
  })

  it('renders nested group depth for structured group navigation', () => {
    const wrapper = mount(WebFormGroup, {
      props: {
        event: EVENT_REPEAT,
        group: repeatGroup({
          depth: 2,
          hierarchy: [
            {
              label: 'Household',
              path: 'household',
              instancePath: 'household[1]',
              repeat: false,
              repeatIndex: null,
              depth: 0,
            },
            {
              label: 'Member',
              path: 'household/members',
              instancePath: 'household[1]/members[1]',
              repeat: true,
              repeatIndex: 0,
              depth: 1,
            },
          ],
        }),
      },
    })

    expect(wrapper.attributes('data-depth')).toBe('2')
    expect(wrapper.text()).toContain('Level 3')
  })

  it('handles JavaRosa new-repeat prompts as repeat junctures', async () => {
    const juncture: RepeatJunctureResponse = {
      label: 'Household Member',
      path: 'household/members/member',
      instancePath: 'household[1]/members[1]/member[@juncture]',
      depth: 1,
      hierarchy: [],
      repeatCount: 0,
      currentRepeatIndex: null,
      repeatTargetCount: null,
      repeatCountControlled: false,
      canAddRepeat: true,
      canDeleteRepeat: false,
    }

    const wrapper = mount(WebFormGroup, {
      props: {
        event: EVENT_PROMPT_NEW_REPEAT,
        group: juncture,
      },
    })

    expect(wrapper.text()).toContain('No entries yet')
    const addButton = wrapper.findAll('button').find((button) => button.text() === 'Add First Entry')
    expect(addButton).toBeDefined()

    await addButton!.trigger('click')
    expect(wrapper.emitted('new-repeat')).toHaveLength(1)
  })
})
