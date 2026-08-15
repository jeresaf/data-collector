import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import WebFormSignature from './WebFormSignature.vue'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('WebFormSignature', () => {
  it('exports a drawn signature as a PNG attachment payload', async () => {
    const context = {
      lineWidth: 0,
      lineCap: '',
      lineJoin: '',
      strokeStyle: '',
      beginPath: vi.fn(),
      moveTo: vi.fn(),
      lineTo: vi.fn(),
      stroke: vi.fn(),
      clearRect: vi.fn(),
    }
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(context as any)
    vi.spyOn(HTMLCanvasElement.prototype, 'getBoundingClientRect').mockReturnValue({
      left: 0, top: 0, right: 400, bottom: 140, width: 400, height: 140, x: 0, y: 0,
      toJSON: () => ({}),
    })
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(callback => {
      const pngBytes = new Uint8Array([137, 80, 78, 71])
      callback({
        type: 'image/png',
        size: pngBytes.byteLength,
        arrayBuffer: async () => pngBytes.buffer,
      } as Blob)
    })

    const wrapper = mount(WebFormSignature)
    const canvas = wrapper.get('canvas')
    await canvas.trigger('pointerdown', { clientX: 20, clientY: 20, pointerId: 1 })
    await canvas.trigger('pointermove', { clientX: 40, clientY: 30, pointerId: 1 })
    await canvas.trigger('pointerup', { clientX: 40, clientY: 30, pointerId: 1 })

    const save = wrapper.findAll('button').find(button => button.text().includes('Save Signature'))!
    await save.trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('save')).toHaveLength(1)

    const payload = wrapper.emitted('save')![0][0] as any
    expect(payload.contentType).toBe('image/png')
    expect(payload.fileName).toMatch(/^signature-\d+\.png$/)
    expect(payload.bytes).toEqual([137, 80, 78, 71])
    expect(context.stroke).toHaveBeenCalled()
  })

  it('requires ink before saving', async () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
    const wrapper = mount(WebFormSignature)
    const save = wrapper.findAll('button').find(button => button.text().includes('Save Signature'))!
    await save.trigger('click')
    expect(wrapper.get('[role="alert"]').text()).toContain('Draw a signature')
    expect(wrapper.emitted('save')).toBeUndefined()
  })
})
