import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import WebFormGeoControl from './WebFormGeoControl.vue'

function position(latitude: number, longitude: number, altitude = 0, accuracy = 5): GeolocationPosition {
  return {
    coords: {
      latitude,
      longitude,
      altitude,
      accuracy,
      altitudeAccuracy: null,
      heading: null,
      speed: null,
      toJSON: () => ({}),
    },
    timestamp: Date.now(),
    toJSON: () => ({}),
  } as GeolocationPosition
}

function mockLocations(...locations: GeolocationPosition[]) {
  const queue = [...locations]
  const getCurrentPosition = vi.fn((success: PositionCallback) => {
    const next = queue.shift()
    if (!next) throw new Error('No mocked location left')
    success(next)
  })
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: { getCurrentPosition },
  })
  return getCurrentPosition
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('WebFormGeoControl', () => {
  it('captures a geopoint in ODK lat lon alt accuracy format', async () => {
    const geolocation = mockLocations(position(0.3476, 32.5825, 120, 4.5))
    const wrapper = mount(WebFormGeoControl, { props: { kind: 'geopoint', value: '' } })

    await wrapper.get('button').trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()

    expect(geolocation).toHaveBeenCalledTimes(1)
    expect(wrapper.emitted('answer')?.at(-1)).toEqual(['0.3476 32.5825 120 4.5'])
  })

  it('builds a geotrace by appending captured points with semicolons', async () => {
    mockLocations(position(0, 32, 0, 5), position(0.1, 32.1, 0, 4))
    const wrapper = mount(WebFormGeoControl, { props: { kind: 'geotrace', value: '' } })
    const add = wrapper.get('button')

    await add.trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()
    await add.trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('answer')?.at(-1)).toEqual(['0 32 0 5;0.1 32.1 0 4'])
    expect(wrapper.get('[aria-label="Geometry preview"]').attributes('aria-label')).toBe('Geometry preview')
  })

  it('closes a geoshape by repeating its first point', async () => {
    mockLocations(
      position(0, 32, 0, 5),
      position(0.1, 32.1, 0, 4),
      position(0.2, 32, 0, 3),
    )
    const wrapper = mount(WebFormGeoControl, { props: { kind: 'geoshape', value: '' } })
    const add = () => wrapper.findAll('button').find(button => button.text().includes('Add Current Point'))!

    await add().trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()
    await add().trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()
    await add().trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()

    const close = wrapper.findAll('button').find(button => button.text().includes('Close Shape'))!
    expect(close.attributes('disabled')).toBeUndefined()
    await close.trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()

    expect(wrapper.emitted('answer')?.at(-1)).toEqual([
      '0 32 0 5;0.1 32.1 0 4;0.2 32 0 3;0 32 0 5',
    ])
  })
})
