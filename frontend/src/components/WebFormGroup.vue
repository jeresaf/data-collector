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
  fieldList?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  fieldList: false,
})

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
  return `Entry ${currentRepeatIndex.value + 1} of ${repeatCount.value}`
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
  if (confirm('Delete this repeat entry?')) {
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
      'group-field-list': fieldList,
    }"
  >
    <header class="group-header">
      <div class="group-title-block">
        <h2 class="group-label">{{ group.label || (isRepeat ? 'Repeat' : 'Section') }}</h2>
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
          Previous entry
        </button>
        <button
          v-if="canMoveNext"
          type="button"
          class="btn-repeat btn-secondary"
          @click="handleDescend((currentRepeatIndex ?? 0) + 1)"
        >
          Next entry
        </button>
        <button
          v-if="canAddRepeat"
          type="button"
          class="btn-repeat btn-primary"
          @click="handleNewRepeat"
        >
          Add another
        </button>
        <button
          v-if="canDeleteRepeat"
          type="button"
          class="btn-repeat btn-danger"
          @click="handleDeleteRepeat"
        >
          Delete entry
        </button>
      </div>

      <div v-else-if="isRepeatJuncture" class="repeat-juncture-controls">
        <p class="juncture-message">
          {{ repeatCount === 0
            ? 'There are no entries yet.'
            : `${repeatCount} entr${repeatCount === 1 ? 'y' : 'ies'} completed.` }}
        </p>
        <button
          v-if="canAddRepeat"
          type="button"
          class="btn-repeat btn-primary"
          @click="handleNewRepeat"
        >
          {{ repeatCount === 0 ? 'Add first entry' : 'Add another entry' }}
        </button>
      </div>
    </header>

    <p v-if="repeatCountControlled" class="repeat-policy" role="status">
      The number of entries is set automatically by this form.
    </p>

    <div class="group-content">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.web-form-group {
  margin-bottom: 1.25rem;
  border: 1px solid #dfe3e8;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}

.group-header {
  padding: 0.9rem 1rem;
  background: #f8fafc;
  border-bottom: 1px solid #e5e7eb;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
}

.group-field-list .group-header {
  background: #f8fafc;
}

.group-title-block {
  display: flex;
  align-items: center;
  gap: 0.7rem;
  flex-wrap: wrap;
}

.group-label {
  margin: 0;
  font-size: 1.08rem;
  line-height: 1.35;
  font-weight: 650;
  color: #1f2937;
}

.repeat-info {
  font-size: 0.8rem;
  font-weight: 600;
  color: #475569;
  background: #fff;
  padding: 0.22rem 0.6rem;
  border-radius: 9999px;
  border: 1px solid #cbd5e1;
}

.repeat-controls,
.repeat-juncture-controls {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  flex-wrap: wrap;
}

.juncture-message {
  margin: 0;
  font-size: 0.875rem;
  color: #64748b;
}

.repeat-policy {
  margin: 0;
  padding: 0.65rem 1rem;
  border-bottom: 1px solid #fde68a;
  background: #fffbeb;
  color: #92400e;
  font-size: 0.8125rem;
}

.group-content {
  padding: 0.25rem 1rem 0.5rem;
}

.group-field-list .group-content {
  padding-top: 0;
}

.btn-repeat {
  padding: 0.48rem 0.75rem;
  border: 1px solid transparent;
  border-radius: 6px;
  font-size: 0.82rem;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
}

.btn-repeat.btn-primary {
  background: #2563eb;
  color: white;
}

.btn-repeat.btn-primary:hover { background: #1d4ed8; }

.btn-repeat.btn-secondary {
  border-color: #cbd5e1;
  background: #fff;
  color: #334155;
}

.btn-repeat.btn-secondary:hover { background: #f8fafc; }

.btn-repeat.btn-danger {
  border-color: #fecaca;
  background: #fff;
  color: #b91c1c;
}

.btn-repeat.btn-danger:hover { background: #fef2f2; }

.group-repeat .group-header {
  background: #fffbeb;
  border-bottom-color: #fde68a;
}

.group-juncture .group-header {
  background: #f0fdf4;
  border-bottom-color: #bbf7d0;
}

@media (max-width: 640px) {
  .group-header {
    align-items: flex-start;
  }

  .repeat-controls,
  .repeat-juncture-controls {
    width: 100%;
  }
}
</style>
