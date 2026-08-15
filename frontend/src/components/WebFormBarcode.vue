<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'

interface Props {
  value?: string | null
  disabled?: boolean
  readOnly?: boolean
  required?: boolean
}

interface BarcodeDetection {
  rawValue?: string
  format?: string
}

interface BarcodeDetectorLike {
  detect(source: HTMLVideoElement): Promise<BarcodeDetection[]>
}

type BarcodeDetectorConstructor = new () => BarcodeDetectorLike

const props = defineProps<Props>()
const emit = defineEmits<{
  (event: 'answer', value: string): void
}>()

const video = ref<HTMLVideoElement | null>(null)
const scanning = ref(false)
const starting = ref(false)
const controlError = ref<string | null>(null)
const lastScan = ref<{ value: string; format?: string } | null>(null)

let stream: MediaStream | null = null
let detector: BarcodeDetectorLike | null = null
let animationFrame: number | null = null
let scannerRun = 0

const inputValue = computed(() => props.value ?? '')
const scannerAvailable = computed(() =>
  Boolean(
    barcodeDetectorConstructor()
    && navigator.mediaDevices
    && typeof navigator.mediaDevices.getUserMedia === 'function',
  ),
)

function barcodeDetectorConstructor(): BarcodeDetectorConstructor | null {
  const target = globalThis as typeof globalThis & {
    BarcodeDetector?: BarcodeDetectorConstructor
  }
  return target.BarcodeDetector ?? null
}

function handleInput(event: Event) {
  const target = event.target as HTMLInputElement
  controlError.value = null
  lastScan.value = null
  emit('answer', target.value)
}

function stopMediaTracks() {
  if (!stream) return
  stream.getTracks().forEach(track => track.stop())
  stream = null
}

function stopScanner() {
  scannerRun += 1
  scanning.value = false
  starting.value = false

  if (animationFrame !== null) {
    cancelAnimationFrame(animationFrame)
    animationFrame = null
  }

  stopMediaTracks()
  detector = null

  if (video.value) {
    video.value.srcObject = null
  }
}

function scheduleScan() {
  if (!scanning.value) return
  animationFrame = requestAnimationFrame(() => {
    void scanFrame()
  })
}

async function scanFrame() {
  if (!scanning.value || !detector || !video.value) return

  try {
    const detections = await detector.detect(video.value)
    if (!scanning.value) return

    const detection = detections.find(item => item.rawValue?.trim())
    if (detection?.rawValue) {
      const value = detection.rawValue.trim()
      lastScan.value = {
        value,
        format: detection.format || undefined,
      }
      controlError.value = null
      emit('answer', value)
      stopScanner()
      return
    }
  } catch (error) {
    // Detection can transiently fail while a camera frame is being prepared.
    // Keep the scanner alive and allow the next frame to retry.
    console.debug('Barcode detection frame failed:', error)
  }

  scheduleScan()
}

async function startScanner() {
  if (props.disabled || props.readOnly || scanning.value || starting.value) return

  controlError.value = null
  lastScan.value = null

  const Detector = barcodeDetectorConstructor()
  if (!Detector) {
    controlError.value = 'Camera barcode scanning is not supported by this browser. Enter the barcode manually.'
    return
  }

  if (!navigator.mediaDevices?.getUserMedia) {
    controlError.value = 'Camera access is not available in this browser. Enter the barcode manually.'
    return
  }

  starting.value = true
  const run = ++scannerRun

  try {
    const requestedStream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: {
        facingMode: { ideal: 'environment' },
      },
    })

    if (run !== scannerRun) {
      requestedStream.getTracks().forEach(track => track.stop())
      return
    }

    stream = requestedStream
    detector = new Detector()
    scanning.value = true
    await nextTick()

    if (!video.value) {
      throw new Error('Camera preview could not be started.')
    }

    video.value.srcObject = stream
    video.value.setAttribute('playsinline', 'true')
    video.value.muted = true
    await video.value.play()

    starting.value = false
    void scanFrame()
  } catch (error) {
    stopScanner()
    const message = error instanceof Error ? error.message : ''
    controlError.value = message
      ? `Unable to use the camera: ${message}`
      : 'Unable to use the camera. Check camera permission and try again.'
  }
}

function toggleScanner() {
  if (scanning.value || starting.value) {
    stopScanner()
  } else {
    void startScanner()
  }
}

onBeforeUnmount(stopScanner)
</script>

<template>
  <div class="barcode-control">
    <div class="barcode-entry">
      <input
        type="text"
        :value="inputValue"
        @input="handleInput"
        placeholder="Enter or scan barcode value"
        :disabled="disabled || readOnly"
        :required="required"
        class="barcode-input"
        aria-label="Barcode value"
      />

      <button
        type="button"
        class="scan-button"
        :class="{ active: scanning }"
        :disabled="disabled || readOnly"
        :aria-pressed="scanning"
        @click="toggleScanner"
      >
        {{ scanning || starting ? 'Stop camera' : 'Scan with camera' }}
      </button>
    </div>

    <div v-if="scanning" class="scanner">
      <div class="video-frame">
        <video
          ref="video"
          class="scanner-video"
          autoplay
          muted
          playsinline
          aria-label="Barcode camera preview"
        ></video>
        <div class="scan-guide" aria-hidden="true"></div>
      </div>
      <p class="scanner-status" role="status">Point the rear camera at the barcode.</p>
    </div>

    <p v-else-if="starting" class="scanner-status" role="status">Starting camera…</p>

    <p v-if="lastScan" class="scan-success" role="status">
      Scanned {{ lastScan.format ? `${lastScan.format}: ` : '' }}{{ lastScan.value }}
    </p>

    <p v-if="controlError" class="validation-error" role="alert">{{ controlError }}</p>

    <p class="barcode-hint">
      {{ scannerAvailable
        ? 'Scan with the camera or enter the barcode manually.'
        : 'Enter the barcode manually. Camera scanning is unavailable in this browser.' }}
    </p>
  </div>
</template>

<style scoped>
.barcode-control {
  display: flex;
  flex-direction: column;
  gap: 0.65rem;
}

.barcode-entry {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0.6rem;
  align-items: stretch;
}

.barcode-input {
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
  padding: 0.75rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 1rem;
}

.barcode-input:focus {
  outline: none;
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

.barcode-input:disabled {
  background: #f9fafb;
  color: #9ca3af;
}

.scan-button {
  padding: 0.7rem 1rem;
  border: 1px solid #2563eb;
  border-radius: 6px;
  background: #2563eb;
  color: #fff;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}

.scan-button:hover:not(:disabled) {
  background: #1d4ed8;
}

.scan-button.active {
  background: #374151;
  border-color: #374151;
}

.scan-button:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.scanner {
  display: flex;
  flex-direction: column;
  gap: 0.45rem;
}

.video-frame {
  position: relative;
  overflow: hidden;
  border-radius: 10px;
  background: #111827;
  aspect-ratio: 4 / 3;
  max-height: 420px;
}

.scanner-video {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.scan-guide {
  position: absolute;
  inset: 26% 10%;
  border: 2px solid rgba(255, 255, 255, 0.92);
  border-radius: 10px;
  box-shadow: 0 0 0 999px rgba(0, 0, 0, 0.2);
  pointer-events: none;
}

.scanner-status,
.barcode-hint,
.scan-success {
  margin: 0;
  font-size: 0.8rem;
}

.scanner-status,
.barcode-hint {
  color: #6b7280;
}

.scan-success {
  color: #047857;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.validation-error {
  margin: 0;
  color: #dc2626;
  font-size: 0.875rem;
}

@media (max-width: 560px) {
  .barcode-entry {
    grid-template-columns: 1fr;
  }

  .scan-button {
    width: 100%;
  }
}
</style>
