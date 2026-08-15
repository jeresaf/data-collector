<script setup lang="ts">
import { computed } from 'vue'
import {
  type GroupResponse,
  type RepeatJunctureResponse,
  EVENT_GROUP,
  EVENT_REPEAT,
  EVENT_REPEAT_JUNCTURE,
  EVENT_PROMPT_NEW_REPEAT,
} from '@/stores/webForm'

interface Props {
  group: GroupResponse | RepeatJunctureResponse
  event: number
}

const props = defineProps<Props>()
const emit = defineEmits<{
  (event: 'descend', index: number): void
  (event: 'new-repeat'): void
  (event: 'delete-repeat'): void
}>()

const isGroup = computed(() => props.event === EVENT_GROUP)
const isRepeat = computed(() => props.event === EVENT_REPEAT)
const isRepeatJuncture = computed(() =>
  props.event === EVENT_REPEAT_JUNCTURE || props.event === EVENT_PROMPT_NEW_REPEAT,
)

const repeatCount = computed(() => Math.max(0, props.group.repeatCount ?? 0))
const currentRepeatIndex = computed(() => props.group.currentRepeatIndex ?? null)
const repeatCountControlled = computed(() => props.group.repeatCountControlled === true)
const canAddRepeat = computed(() => props.group.canAddRepeat === true)
const canDeleteRepeat = computed(() => props.group.canDeleteRepeat === true)

const canMovePrevious = computed(() =>
  isRepeat.value
  && currentRepeatIndex.value !== null
  && currentRepeatIndex.value > 0,
)

const canMoveNext = computed(() =>
  isRepeat.value
  && currentRepeatIndex.value !== null
  && currentRepeatIndex.value < repeatCount.value - 1,
)

const repeatPositionLabel = computed(() => {
  if (currentRepeatIndex.value === null) {
    return `${repeatCount.value} ${repeatCount.value === 1 ? 'entry' : 'entries'}`
  }
  return `Instance ${currentRepeatIndex.value + 1} of ${repeatCount.value}`
})

function handleDescend(index: number) {
  if (index < 0 || index >= repeatCount.value) return
  emit('descend', index)
}

function handleNewRepeat() {
  if (!canAddRepeat.value) return
  emit('new-repeat')
}

function handleDeleteRepeat() {
  if (!canDeleteRepeat.value) return
  if (confirm('Are you sure you want to delete this repeat instance?')) {
    emit('delete-repeat')
  }
}
</script>

<template>
  <section
    class="web-form-group"
    :data-depth="group.depth ?? 0"
    :class="{
      'group-standard': isGroup,
      'group-repeat': isRepeat,
      'group-juncture': isRepeatJuncture,
    }"
  >
    <div class="group-header">
      <div class="group-title-block">
        <h3 class="group-label">{{ group.label || (isRepeat ? 'Repeat' : 'Group') }}</h3>
        <span v-if="(group.depth ?? 0) > 0" class="group-depth" aria-label="Nested section level">
          Level {{ (group.depth ?? 0) + 1 }}
        </span>
        <span v-if="isRepeat || isRepeatJuncture" class="repeat-info">
          {{ repeatPositionLabel }}
        </span>
      </div>

      <div v-if="isRepeat" class="repeat-controls" aria-label="Repeat controls">
        <button
          v-if="canMovePrevious"
          type="button"
          class="btn-repeat btn-secondary"
          @click="handleDescend((currentRepeatIndex ?? 0) - 1)"
        >
          Previous
        </button>
        <button
          v-if="canMoveNext"
          type="button"
          class="btn-repeat btn-secondary"
          @click="handleDescend((currentRepeatIndex ?? 0) + 1)"
        >
          Next
        </button>
        <button
          v-if="canAddRepeat"
          type="button"
          class="btn-repeat btn-primary"
          @click="handleNewRepeat"
        >
          Add Another
        </button>
        <button
          v-if="canDeleteRepeat"
          type="button"
          class="btn-repeat btn-danger"
          @click="handleDeleteRepeat"
        >
          Delete This
        </button>
      </div>

      <div v-else-if="isRepeatJuncture" class="repeat-juncture-controls">
        <p class="juncture-message">
          {{ repeatCount === 0
            ? 'No entries yet.'
            : `You have ${repeatCount} entr${repeatCount === 1 ? 'y' : 'ies'}.` }}
        </p>
        <button
          v-if="canAddRepeat"
          type="button"
          class="btn-repeat btn-primary"
          @click="handleNewRepeat"
        >
          {{ repeatCount === 0 ? 'Add First Entry' : 'Add Another Entry' }}
        </button>
      </div>
    </div>

    <p v-if="repeatCountControlled" class="repeat-policy" role="status">
      This repeat is controlled by the form definition. Entries are created automatically and cannot be added or deleted manually.
    </p>

    <div class="group-content">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.web-form-group {
  margin-bottom: 1.5rem;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  overflow: hidden;
}

.group-header {
  padding: 1rem;
  background: #f9fafb;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.group-title-block {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  flex-wrap: wrap;
}

.group-label {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 600;
  color: #1f2937;
}

.group-depth {
  font-size: 0.75rem;
  color: #4b5563;
  background: #f3f4f6;
  padding: 0.2rem 0.5rem;
  border-radius: 9999px;
}

.web-form-group[data-depth="1"] {
  margin-left: 0.5rem;
}

.web-form-group[data-depth="2"],
.web-form-group[data-depth="3"] {
  margin-left: 1rem;
}

.repeat-info {
  font-size: 0.875rem;
  color: #6b7280;
  background: #fff;
  padding: 0.25rem 0.75rem;
  border-radius: 9999px;
  border: 1px solid #d1d5db;
}

.repeat-controls,
.repeat-juncture-controls {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-wrap: wrap;
}

.juncture-message {
  margin: 0;
  font-size: 0.875rem;
  color: #6b7280;
}

.repeat-policy {
  margin: 0;
  padding: 0.75rem 1rem;
  border-bottom: 1px solid #fde68a;
  background: #fffbeb;
  color: #92400e;
  font-size: 0.8125rem;
}

.group-content {
  padding: 1rem;
}

.btn-repeat {
  padding: 0.5rem 1rem;
  border: none;
  border-radius: 6px;
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.btn-repeat.btn-primary {
  background: #3b82f6;
  color: white;
}

.btn-repeat.btn-primary:hover {
  background: #2563eb;
}

.btn-repeat.btn-secondary {
  background: #e5e7eb;
  color: #374151;
}

.btn-repeat.btn-secondary:hover {
  background: #d1d5db;
}

.btn-repeat.btn-danger {
  background: #fee2e2;
  color: #dc2626;
}

.btn-repeat.btn-danger:hover {
  background: #fecaca;
}

.group-repeat .group-header {
  background: #fef3c7;
  border-bottom-color: #fde68a;
}

.group-juncture .group-header {
  background: #f0fdf4;
  border-bottom-color: #bbf7d0;
}
</style>
