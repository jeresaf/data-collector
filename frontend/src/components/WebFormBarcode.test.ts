import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import WebFormBarcode from './WebFormBarcode.vue'

function setMediaDevices(getUserMedia?: ReturnType<typeof vi.fn>) {
  Object.defineProperty(navigator, 'mediaDevices', {
    configurable: true,
    value: getUserMedia ? { getUserMedia } : undefined,
  })
}

describe('WebFormBarcode', () => {
  beforeEach(() => {
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      return window.setTimeout(() => callback(performance.now()), 0)
    })
    vi.stubGlobal('cancelAnimationFrame', (id: number) => window.clearTimeout(id))
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    setMediaDevices()
  })

  it('keeps manual barcode entry available', async () => {
    const wrapper = mount(WebFormBarcode, { props: { value: '' } })

    await wrapper.get('input[aria-label="Barcode value"]').setValue('MANUAL-123')

    expect(wrapper.emitted('answer')).toEqual([['MANUAL-123']])
  })

  it('shows a manual-entry fallback when BarcodeDetector is unavailable', async () => {
    setMediaDevices(vi.fn())
    const wrapper = mount(WebFormBarcode, { props: { value: '' } })

    await wrapper.get('button').trigger('click')

    expect(wrapper.get('[role="alert"]').text()).toContain('not supported')
    expect(wrapper.get('input[aria-label="Barcode value"]').exists()).toBe(true)
  })

  it('opens the rear camera, scans a barcode, emits the raw value and stops the stream', async () => {
    const stop = vi.fn()
    const stream = {
      getTracks: () => [{ stop }],
    } as unknown as MediaStream

    const getUserMedia = vi.fn().mockResolvedValue(stream)
    setMediaDevices(getUserMedia)

    const detect = vi.fn().mockResolvedValue([
      { rawValue: ' 0123456789012 ', format: 'ean_13' },
    ])

    vi.stubGlobal('BarcodeDetector', class {
      detect = detect
    })

    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue()

    const wrapper = mount(WebFormBarcode, { props: { value: '' } })
    await wrapper.get('button').trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()
    await flushPromises()

    expect(getUserMedia).toHaveBeenCalledWith({
      audio: false,
      video: { facingMode: { ideal: 'environment' } },
    })
    expect(detect).toHaveBeenCalled()
    expect(wrapper.emitted('answer')).toContainEqual(['0123456789012'])
    expect(stop).toHaveBeenCalled()
    expect(wrapper.text()).toContain('ean_13')
    expect(wrapper.text()).toContain('0123456789012')
  })

  it('stops the active camera when the control is unmounted', async () => {
    const stop = vi.fn()
    const stream = {
      getTracks: () => [{ stop }],
    } as unknown as MediaStream

    setMediaDevices(vi.fn().mockResolvedValue(stream))
    vi.stubGlobal('BarcodeDetector', class {
      detect = vi.fn().mockResolvedValue([])
    })
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue()

    const wrapper = mount(WebFormBarcode, { props: { value: '' } })
    await wrapper.get('button').trigger('click')
    await flushPromises()

    wrapper.unmount()

    expect(stop).toHaveBeenCalled()
  })
})
