<script setup lang="ts">
import { computed, onMounted, reactive } from 'vue'
import {
  useProjectSettingsStore,
  type ProjectSettingScalar,
  type ProjectSettingValue,
} from '@/stores/projectSettings'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{
  projectId: string
}>()

const store = useProjectSettingsStore()
const authStore = useAuthStore()

const drafts = reactive<Record<string, ProjectSettingScalar>>({})
const savedKeys = reactive<Record<string, boolean>>({})

const knownOrder = [
  'allow_anonymous_submissions',
  'require_review_before_approval',
  'default_form_status',
  'max_submission_size_mb',
  'timezone',
  'supported_languages',
]

const canManage = computed(() => {
  const roles = authStore.user?.roles ?? []
  return (
    authStore.hasPermission('PROJECT_UPDATE') ||
    roles.some(role =>
      ['SYSTEM_ADMIN', 'ADMIN', 'PROJECT_ADMIN', 'PROJECT_MANAGER'].includes(role),
    )
  )
})

const entries = computed(() =>
  Object.entries(store.settings).sort(([left], [right]) => {
    const leftIndex = knownOrder.indexOf(left)
    const rightIndex = knownOrder.indexOf(right)

    if (leftIndex === -1 && rightIndex === -1) return left.localeCompare(right)
    if (leftIndex === -1) return 1
    if (rightIndex === -1) return -1
    return leftIndex - rightIndex
  }),
)

const labels: Record<string, string> = {
  allow_anonymous_submissions: 'Allow anonymous submissions',
  require_review_before_approval: 'Require review before approval',
  default_form_status: 'Default form status',
  max_submission_size_mb: 'Maximum submission size',
  timezone: 'Project timezone',
  supported_languages: 'Supported languages',
}

function labelFor(key: string): string {
  if (labels[key]) return labels[key]
  return key
    .replace(/_/g, ' ')
    .replace(/\b\w/g, character => character.toUpperCase())
}

function descriptionFor(setting: ProjectSettingValue): string {
  return typeof setting.description === 'string' ? setting.description : ''
}

function actualValue(key: string, setting: ProjectSettingValue): ProjectSettingScalar {
  return Object.prototype.hasOwnProperty.call(drafts, key)
    ? drafts[key]
    : setting.value
}

function setDraft(key: string, value: ProjectSettingScalar) {
  drafts[key] = value
  savedKeys[key] = false
}

function isBoolean(value: ProjectSettingScalar): value is boolean {
  return typeof value === 'boolean'
}

function isNumber(value: ProjectSettingScalar): value is number {
  return typeof value === 'number'
}

function isStringArray(value: ProjectSettingScalar): value is string[] {
  return Array.isArray(value)
}

function languagesText(value: ProjectSettingScalar): string {
  return Array.isArray(value) ? value.join(', ') : ''
}

function parseLanguages(value: string): string[] {
  return [...new Set(
    value
      .split(',')
      .map(item => item.trim())
      .filter(Boolean),
  )]
}

async function save(key: string, setting: ProjectSettingValue) {
  const value = actualValue(key, setting)
  await store.updateSetting(
    props.projectId,
    key,
    value,
    descriptionFor(setting) || undefined,
  )
  delete drafts[key]
  savedKeys[key] = true
}

async function initialize() {
  await store.initializeDefaults(props.projectId)
}

onMounted(async () => {
  await store.fetchSettings(props.projectId)
})
</script>

<template>
  <section class="project-settings-panel" aria-labelledby="project-settings-title">
    <header class="panel-header">
      <div>
        <h2 id="project-settings-title">Project Settings</h2>
        <p>Configure project-level submission and form defaults.</p>
      </div>

      <button
        v-if="store.isEmpty && canManage"
        class="btn btn-primary"
        type="button"
        :disabled="store.loading"
        @click="initialize"
      >
        {{ store.loading ? 'Initializing…' : 'Initialize defaults' }}
      </button>
    </header>

    <div v-if="store.error" class="alert error" role="alert">
      {{ store.error }}
    </div>

    <div v-if="store.loading && store.isEmpty" class="loading-state">
      Loading project settings…
    </div>

    <div v-else-if="store.isEmpty" class="empty-state">
      <h3>No settings configured</h3>
      <p>
        This project does not have project-level settings yet.
        <span v-if="canManage">Initialize the standard defaults to begin.</span>
      </p>
    </div>

    <div v-else class="settings-list">
      <article
        v-for="[key, setting] in entries"
        :key="key"
        class="setting-card"
        :data-setting-key="key"
      >
        <div class="setting-copy">
          <h3>{{ labelFor(key) }}</h3>
          <p v-if="descriptionFor(setting)">{{ descriptionFor(setting) }}</p>
          <code>{{ key }}</code>
        </div>

        <div class="setting-control">
          <template v-if="isBoolean(actualValue(key, setting))">
            <label class="switch-row">
              <input
                type="checkbox"
                :checked="actualValue(key, setting) === true"
                :disabled="!canManage || store.savingKey === key"
                @change="setDraft(key, ($event.target as HTMLInputElement).checked)"
              />
              <span>{{ actualValue(key, setting) ? 'Enabled' : 'Disabled' }}</span>
            </label>
          </template>

          <template v-else-if="key === 'default_form_status'">
            <select
              :value="String(actualValue(key, setting) ?? '')"
              :disabled="!canManage || store.savingKey === key"
              :aria-label="labelFor(key)"
              @change="setDraft(key, ($event.target as HTMLSelectElement).value)"
            >
              <option value="draft">Draft</option>
              <option value="validated">Validated</option>
              <option value="published">Published</option>
            </select>
          </template>

          <template v-else-if="isNumber(actualValue(key, setting))">
            <div class="number-control">
              <input
                type="number"
                min="1"
                step="1"
                :value="actualValue(key, setting) as number"
                :disabled="!canManage || store.savingKey === key"
                :aria-label="labelFor(key)"
                @input="setDraft(
                  key,
                  Number(($event.target as HTMLInputElement).value),
                )"
              />
              <span v-if="key === 'max_submission_size_mb'">MB</span>
            </div>
          </template>

          <template v-else-if="isStringArray(actualValue(key, setting))">
            <input
              type="text"
              :value="languagesText(actualValue(key, setting))"
              :disabled="!canManage || store.savingKey === key"
              :aria-label="labelFor(key)"
              placeholder="en, fr, sw"
              @input="setDraft(
                key,
                parseLanguages(($event.target as HTMLInputElement).value),
              )"
            />
            <small>Comma-separated language codes</small>
          </template>

          <template v-else>
            <input
              type="text"
              :value="String(actualValue(key, setting) ?? '')"
              :disabled="!canManage || store.savingKey === key"
              :aria-label="labelFor(key)"
              @input="setDraft(
                key,
                ($event.target as HTMLInputElement).value,
              )"
            />
          </template>

          <div class="setting-actions">
            <span v-if="savedKeys[key]" class="saved-message" role="status">Saved</span>
            <button
              v-if="canManage"
              class="btn btn-primary btn-small"
              type="button"
              :disabled="
                !Object.prototype.hasOwnProperty.call(drafts, key) ||
                store.savingKey === key
              "
              @click="save(key, setting)"
            >
              {{ store.savingKey === key ? 'Saving…' : 'Save' }}
            </button>
          </div>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.project-settings-panel {
  display: grid;
  gap: 20px;
}

.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.panel-header h2,
.setting-card h3,
.empty-state h3 {
  margin: 0;
  color: #111827;
}

.panel-header p {
  margin: 6px 0 0;
  color: #6b7280;
}

.settings-list {
  display: grid;
  gap: 12px;
}

.setting-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(260px, 0.8fr);
  gap: 24px;
  align-items: center;
  padding: 18px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fff;
}

.setting-copy p {
  margin: 6px 0 8px;
  color: #6b7280;
  font-size: 14px;
  line-height: 1.45;
}

.setting-copy code {
  color: #6b7280;
  font-size: 12px;
}

.setting-control {
  display: grid;
  gap: 8px;
}

.setting-control input[type='text'],
.setting-control input[type='number'],
.setting-control select {
  width: 100%;
  box-sizing: border-box;
  min-height: 40px;
  padding: 8px 10px;
  border: 1px solid #d1d5db;
  border-radius: 7px;
  background: #fff;
  color: #111827;
  font: inherit;
}

.setting-control input:disabled,
.setting-control select:disabled {
  background: #f9fafb;
  color: #6b7280;
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 40px;
  color: #374151;
  font-weight: 600;
}

.switch-row input {
  width: 18px;
  height: 18px;
}

.number-control {
  display: flex;
  align-items: center;
  gap: 8px;
}

.number-control span,
.setting-control small {
  color: #6b7280;
  font-size: 12px;
}

.setting-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  min-height: 32px;
  gap: 10px;
}

.btn-small {
  padding: 6px 12px;
}

.saved-message {
  color: #166534;
  font-size: 13px;
  font-weight: 600;
}

.alert,
.loading-state,
.empty-state {
  padding: 16px;
  border-radius: 8px;
}

.alert.error {
  background: #fef2f2;
  color: #991b1b;
}

.loading-state,
.empty-state {
  border: 1px dashed #d1d5db;
  background: #f9fafb;
  color: #6b7280;
}

.empty-state p {
  margin: 6px 0 0;
}

@media (max-width: 760px) {
  .panel-header,
  .setting-card {
    grid-template-columns: 1fr;
  }

  .panel-header {
    flex-direction: column;
  }

  .setting-card {
    gap: 14px;
  }
}
</style>
