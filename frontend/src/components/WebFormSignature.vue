<script setup lang="ts">
import { ref } from 'vue'

interface Props {
  disabled?: boolean
  existingFileName?: string | null
}

const props = withDefaults(defineProps<Props>(), {
  disabled: false,
  existingFileName: null,
})

const emit = defineEmits<{
  (event: 'save', payload: { fileName: string; contentType: string; bytes: number[] }): void
}>()

const canvas = ref<HTMLCanvasElement | null>(null)
const drawing = ref(false)
const hasInk = ref(false)
const saving = ref(false)
const controlError = ref<string | null>(null)

function context(): CanvasRenderingContext2D | null {
  const ctx = canvas.value?.getContext('2d') ?? null
  if (ctx) {
    ctx.lineWidth = 3
    ctx.lineCap = 'round'
    ctx.lineJoin = 'round'
    ctx.strokeStyle = '#111827'
  }
  return ctx
}

function pointFromEvent(event: PointerEvent) {
  const element = canvas.value
  if (!element) return { x: 0, y: 0 }
  const rect = element.getBoundingClientRect()
  const scaleX = rect.width ? element.width / rect.width : 1
  const scaleY = rect.height ? element.height / rect.height : 1
  return {
    x: (event.clientX - rect.left) * scaleX,
    y: (event.clientY - rect.top) * scaleY,
  }
}

function startDrawing(event: PointerEvent) {
  if (props.disabled || !canvas.value) return
  const ctx = context()
  if (!ctx) {
    controlError.value = 'Signature drawing is not available in this browser.'
    return
  }

  drawing.value = true
  hasInk.value = true
  controlError.value = null
  canvas.value.setPointerCapture?.(event.pointerId)
  const point = pointFromEvent(event)
  ctx.beginPath()
  ctx.moveTo(point.x, point.y)
}

function continueDrawing(event: PointerEvent) {
  if (props.disabled || !drawing.value) return
  const ctx = context()
  if (!ctx) return
  const point = pointFromEvent(event)
  ctx.lineTo(point.x, point.y)
  ctx.stroke()
}

function stopDrawing(event?: PointerEvent) {
  drawing.value = false
  if (event && canvas.value?.hasPointerCapture?.(event.pointerId)) {
    canvas.value.releasePointerCapture?.(event.pointerId)
  }
}

function clearSignature() {
  if (props.disabled) return
  const element = canvas.value
  const ctx = context()
  if (element && ctx) ctx.clearRect(0, 0, element.width, element.height)
  hasInk.value = false
  controlError.value = null
}

async function saveSignature() {
  if (props.disabled) return
  const element = canvas.value
  if (!element || !hasInk.value) {
    controlError.value = 'Draw a signature before saving.'
    return
  }

  saving.value = true
  controlError.value = null
  try {
    const blob = await new Promise<Blob>((resolve, reject) => {
      element.toBlob(result => {
        if (result) resolve(result)
        else reject(new Error('Unable to create the signature image.'))
      }, 'image/png')
    })
    const bytes = Array.from(new Uint8Array(await blob.arrayBuffer()))
    emit('save', {
      fileName: `signature-${Date.now()}.png`,
      contentType: 'image/png',
      bytes,
    })
  } catch (error) {
    controlError.value = error instanceof Error ? error.message : 'Unable to save the signature.'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="signature-control">
    <canvas
      ref="canvas"
      class="signature-canvas"
      width="800"
      height="280"
      aria-label="Signature drawing area"
      @pointerdown="startDrawing"
      @pointermove="continueDrawing"
      @pointerup="stopDrawing"
      @pointercancel="stopDrawing"
      @pointerleave="stopDrawing"
    ></canvas>
    <div class="signature-line" aria-hidden="true"></div>
    <div class="signature-actions">
      <button type="button" class="btn-primary" :disabled="disabled || saving" @click="saveSignature">
        {{ saving ? 'Saving…' : 'Save Signature' }}
      </button>
      <button type="button" class="btn-secondary" :disabled="disabled || saving || !hasInk" @click="clearSignature">
        Clear
      </button>
    </div>
    <p v-if="existingFileName" class="signature-saved">Saved: {{ existingFileName }}</p>
    <p v-if="controlError" class="validation-error" role="alert">{{ controlError }}</p>
  </div>
</template>

<style scoped>
.signature-control {
  display: flex;
  flex-direction: column;
  gap: 0.65rem;
}

.signature-canvas {
  width: 100%;
  height: min(34vw, 220px);
  min-height: 150px;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  background: white;
  cursor: crosshair;
  touch-action: none;
}

.signature-line {
  height: 1px;
  background: #94a3b8;
  margin: -2.3rem 8% 1.25rem;
  pointer-events: none;
}

.signature-actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.btn-primary,
.btn-secondary {
  min-height: 42px;
  padding: 0.55rem 0.9rem;
  border-radius: 6px;
  font-size: 0.875rem;
  cursor: pointer;
}

.btn-primary {
  border: none;
  background: #2563eb;
  color: white;
}

.btn-secondary {
  border: 1px solid #d1d5db;
  background: white;
  color: #374151;
}

.btn-primary:disabled,
.btn-secondary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.signature-saved {
  margin: 0;
  color: #059669;
  font-size: 0.875rem;
}

.validation-error {
  margin: 0;
  color: #dc2626;
  font-size: 0.875rem;
}
</style>
