<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import {
  closeGeoShape,
  geometryPreviewPoints,
  isClosedGeoShape,
  parseGeoPath,
  parseGeoPoint,
  pointFromGeolocation,
  serializeGeoPath,
  serializeGeoPoint,
  type OdkGeoPoint,
} from '@/utils/geo'

interface Props {
  kind: 'geopoint' | 'geotrace' | 'geoshape'
  value?: string | null
  disabled?: boolean
  readOnly?: boolean
  required?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  value: '',
  disabled: false,
  readOnly: false,
  required: false,
})

const emit = defineEmits<{
  (event: 'answer', value: string): void
}>()

const rawValue = ref(props.value ?? '')
const controlError = ref<string | null>(null)
const capturing = ref(false)

watch(() => props.value, value => {
  const next = value ?? ''
  if (next !== rawValue.value) rawValue.value = next
})

const locked = computed(() => props.disabled || props.readOnly)
const points = computed(() => props.kind === 'geopoint'
  ? (parseGeoPoint(rawValue.value) ? [parseGeoPoint(rawValue.value)!] : [])
  : parseGeoPath(rawValue.value))
const previewPoints = computed(() => geometryPreviewPoints(points.value))
const shapeClosed = computed(() => props.kind === 'geoshape' && isClosedGeoShape(points.value))
const canCloseShape = computed(() => props.kind === 'geoshape' && points.value.length >= 3 && !shapeClosed.value)

function emitValue(value: string) {
  rawValue.value = value
  emit('answer', value)
}

function handleRawInput(event: Event) {
  const target = event.target as HTMLInputElement | HTMLTextAreaElement
  rawValue.value = target.value
  controlError.value = null
  emit('answer', target.value)
}

function capturePosition(): Promise<OdkGeoPoint> {
  controlError.value = null
  if (!navigator.geolocation) {
    return Promise.reject(new Error('Location is not supported by this browser.'))
  }

  capturing.value = true
  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      position => resolve(pointFromGeolocation(position)),
      error => reject(new Error(error.message || 'Unable to get the current location.')),
      { enableHighAccuracy: true },
    )
  }).finally(() => {
    capturing.value = false
  })
}

async function useCurrentPoint() {
  try {
    const point = await capturePosition()
    emitValue(serializeGeoPoint(point))
  } catch (error) {
    controlError.value = error instanceof Error ? error.message : 'Unable to get the current location.'
  }
}

async function addCurrentPoint() {
  try {
    const point = await capturePosition()
    const current = parseGeoPath(rawValue.value)
    const next = shapeClosed.value ? current.slice(0, -1) : current
    emitValue(serializeGeoPath([...next, point]))
  } catch (error) {
    controlError.value = error instanceof Error ? error.message : 'Unable to get the current location.'
  }
}

function removeLastPoint() {
  const current = parseGeoPath(rawValue.value)
  if (current.length === 0) return
  const next = shapeClosed.value ? current.slice(0, -1) : current
  next.pop()
  emitValue(serializeGeoPath(next))
}

function clearGeometry() {
  controlError.value = null
  emitValue('')
}

function closeShape() {
  try {
    const current = parseGeoPath(rawValue.value)
    emitValue(serializeGeoPath(closeGeoShape(current)))
    controlError.value = null
  } catch (error) {
    controlError.value = error instanceof Error ? error.message : 'Unable to close the shape.'
  }
}
</script>

<template>
  <div class="geo-control" :data-geo-kind="kind">
    <template v-if="kind === 'geopoint'">
      <input
        type="text"
        :value="rawValue"
        @input="handleRawInput"
        placeholder="Latitude longitude altitude accuracy (e.g., 0.3476 32.5825 0 5)"
        :disabled="locked"
        :required="required"
        class="form-input"
        aria-label="Geopoint value"
      />
      <button type="button" class="btn-geo" @click="useCurrentPoint" :disabled="locked || capturing">
        {{ capturing ? 'Capturing location…' : 'Use Current Location' }}
      </button>
    </template>

    <template v-else>
      <div v-if="points.length" class="geo-preview" aria-label="Geometry preview">
        <svg viewBox="0 0 300 160" role="img" :aria-label="kind === 'geoshape' ? 'Geoshape preview' : 'Geotrace preview'">
          <polyline
            :points="previewPoints"
            :class="['geo-line', { 'geo-shape': kind === 'geoshape' }]"
          />
          <circle
            v-for="(point, index) in points"
            :key="`${point.latitude}-${point.longitude}-${index}`"
            :cx="Number(previewPoints.split(' ')[index]?.split(',')[0] || 0)"
            :cy="Number(previewPoints.split(' ')[index]?.split(',')[1] || 0)"
            r="4"
            class="geo-point"
          />
        </svg>
      </div>

      <textarea
        :value="rawValue"
        @input="handleRawInput"
        placeholder="ODK coordinates separated by semicolons: lat lon alt acc;lat lon alt acc"
        :disabled="locked"
        :required="required"
        rows="4"
        class="form-textarea"
        :aria-label="kind === 'geoshape' ? 'Geoshape value' : 'Geotrace value'"
      ></textarea>

      <div class="geo-actions">
        <button type="button" class="btn-geo" @click="addCurrentPoint" :disabled="locked || capturing || shapeClosed">
          {{ capturing ? 'Capturing…' : 'Add Current Point' }}
        </button>
        <button type="button" class="btn-secondary" @click="removeLastPoint" :disabled="locked || points.length === 0">
          Remove Last
        </button>
        <button
          v-if="kind === 'geoshape'"
          type="button"
          class="btn-secondary"
          @click="closeShape"
          :disabled="locked || !canCloseShape"
        >
          {{ shapeClosed ? 'Shape Closed' : 'Close Shape' }}
        </button>
        <button type="button" class="btn-secondary" @click="clearGeometry" :disabled="locked || !rawValue">
          Clear
        </button>
      </div>

      <p class="geo-hint">
        {{ kind === 'geoshape'
          ? 'Capture at least three points, then close the polygon. ODK stores the closing point again at the end.'
          : 'Capture points in order to build the line. ODK stores points separated by semicolons.' }}
      </p>
      <p class="geo-count">{{ points.length }} {{ points.length === 1 ? 'point' : 'points' }} captured</p>
    </template>

    <p v-if="controlError" class="validation-error" role="alert">{{ controlError }}</p>
  </div>
</template>

<style scoped>
.geo-control {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.form-input,
.form-textarea {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 1rem;
  box-sizing: border-box;
}

.geo-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.btn-geo,
.btn-secondary {
  min-height: 42px;
  padding: 0.55rem 0.9rem;
  border-radius: 6px;
  font-size: 0.875rem;
  cursor: pointer;
}

.btn-geo {
  border: none;
  background: #2563eb;
  color: white;
}

.btn-secondary {
  border: 1px solid #d1d5db;
  background: white;
  color: #374151;
}

.btn-geo:disabled,
.btn-secondary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.geo-preview {
  width: 100%;
  overflow: hidden;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  background: #f8fafc;
}

.geo-preview svg {
  display: block;
  width: 100%;
  min-height: 150px;
}

.geo-line {
  fill: none;
  stroke: currentColor;
  stroke-width: 3;
  stroke-linejoin: round;
  stroke-linecap: round;
}

.geo-shape {
  fill: rgba(37, 99, 235, 0.12);
}

.geo-point {
  fill: currentColor;
}

.geo-hint,
.geo-count {
  margin: 0;
  color: #6b7280;
  font-size: 0.78rem;
}

.validation-error {
  color: #dc2626;
  font-size: 0.875rem;
  margin: 0;
}

@media (max-width: 640px) {
  .geo-actions > button {
    flex: 1 1 calc(50% - 0.5rem);
  }
}
</style>
