<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useFormLifecycleStore, type FormVersionSummary } from '@/stores/formLifecycle'
import FormAssignmentsPanel from '@/components/FormAssignmentsPanel.vue'
import FormMediaPanel from '@/components/FormMediaPanel.vue'
import FormExternalDataPanel from '@/components/FormExternalDataPanel.vue'
import MobileFormAssignmentsPanel from '@/components/MobileFormAssignmentsPanel.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const lifecycle = useFormLifecycleStore()

const projectId = computed(() => String(route.params.projectId || ''))
const formId = computed(() => String(route.params.formId || ''))
const activeTab = ref<'overview' | 'versions' | 'media' | 'external-data' | 'history' | 'mobile-access' | 'access'>('overview')
const notice = ref('')

const canManage = computed(() =>
  auth.hasPermission('FORM_UPDATE') ||
  auth.hasPermission('PROJECT_UPDATE') ||
  ['SYSTEM_ADMIN', 'ADMIN', 'PROJECT_ADMIN', 'PROJECT_MANAGER'].some((role) => auth.hasRole(role)),
)

const title = computed(() => lifecycle.form?.title || lifecycle.form?.name || 'Form')

function formatDate(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}

function statusLabel(version: FormVersionSummary): string {
  if (version.isPublished) return 'PUBLISHED'
  return version.status || 'DRAFT'
}

async function load(): Promise<void> {
  if (!projectId.value || !formId.value) return
  try {
    await lifecycle.load(projectId.value, formId.value)
  } catch {
    // Store exposes the user-facing error.
  }
}

async function publish(version: FormVersionSummary): Promise<void> {
  if (!canManage.value || version.isPublished || !lifecycle.form?.isActive) return
  notice.value = ''
  try {
    await lifecycle.publishVersion(projectId.value, formId.value, version.id)
    notice.value = `Version ${version.version} published.`
  } catch {
    // Store exposes the user-facing error.
  }
}

async function archive(): Promise<void> {
  if (!canManage.value || !lifecycle.form?.isActive) return
  if (!window.confirm(`Archive ${title.value}? It will no longer be distributed to data collectors.`)) return
  notice.value = ''
  try {
    await lifecycle.archiveForm(projectId.value, formId.value)
    notice.value = 'Form archived.'
  } catch {
    // Store exposes the user-facing error.
  }
}

function backToProject(): void {
  router.push({ name: 'project-detail', params: { id: projectId.value } })
}

onMounted(load)
</script>

<template>
  <main class="form-detail-page">
    <button class="back-link" type="button" @click="backToProject">← Back to project</button>

    <div v-if="lifecycle.loading && !lifecycle.form" class="state-card">Loading form lifecycle…</div>

    <div v-else-if="lifecycle.error && !lifecycle.form" class="state-card error-card">
      <strong>Unable to load form.</strong>
      <span>{{ lifecycle.error }}</span>
      <button type="button" class="btn secondary" @click="load">Retry</button>
    </div>

    <template v-else-if="lifecycle.form">
      <header class="page-header">
        <div>
          <div class="eyebrow">Form administration</div>
          <div class="title-row">
            <h1>{{ title }}</h1>
            <span class="status-pill" :class="lifecycle.form.isPublished ? 'published' : lifecycle.form.isActive ? 'draft' : 'archived'">
              {{ lifecycle.form.isPublished ? 'Published' : lifecycle.form.isActive ? 'Not published' : 'Archived' }}
            </span>
          </div>
          <p v-if="lifecycle.form.description" class="description">{{ lifecycle.form.description }}</p>
          <p class="identity">Form ID: <code>{{ lifecycle.form.xmlns || lifecycle.form.id }}</code></p>
        </div>
        <div v-if="canManage" class="header-actions">
          <button
            type="button"
            class="btn danger"
            :disabled="lifecycle.saving || !lifecycle.form.isActive"
            @click="archive"
          >
            {{ lifecycle.form.isActive ? 'Archive form' : 'Archived' }}
          </button>
        </div>
      </header>

      <p v-if="notice" class="notice success">{{ notice }}</p>
      <p v-if="lifecycle.error" class="notice error">{{ lifecycle.error }}</p>

      <nav class="tabs" aria-label="Form lifecycle sections">
        <button type="button" :class="{ active: activeTab === 'overview' }" @click="activeTab = 'overview'">Overview</button>
        <button type="button" :class="{ active: activeTab === 'versions' }" @click="activeTab = 'versions'">Versions ({{ lifecycle.versions.length }})</button>
        <button type="button" :class="{ active: activeTab === 'media' }" @click="activeTab = 'media'">Media</button>
        <button type="button" :class="{ active: activeTab === 'external-data' }" @click="activeTab = 'external-data'">External Data</button>
        <button type="button" :class="{ active: activeTab === 'history' }" @click="activeTab = 'history'">Publish history ({{ lifecycle.history.length }})</button>
        <button v-if="canManage" type="button" :class="{ active: activeTab === 'mobile-access' }" @click="activeTab = 'mobile-access'">Mobile assignments</button>
        <button type="button" :class="{ active: activeTab === 'access' }" @click="activeTab = 'access'">Web access</button>
      </nav>

      <section v-if="activeTab === 'overview'" class="panel">
        <h2>Lifecycle overview</h2>
        <div class="summary-grid">
          <div class="summary-card"><span>Current XForm version</span><strong>{{ lifecycle.form.version || '—' }}</strong></div>
          <div class="summary-card"><span>Stored versions</span><strong>{{ lifecycle.versions.length }}</strong></div>
          <div class="summary-card"><span>Current version ID</span><strong class="small-value">{{ lifecycle.form.formVersionId || 'None' }}</strong></div>
          <div class="summary-card"><span>Last updated</span><strong class="small-value">{{ formatDate(lifecycle.form.updatedAt) }}</strong></div>
        </div>

        <div class="guidance">
          <h3>Publishing rule</h3>
          <p>Select the exact validated version you intend to distribute. Publishing a version makes it the current published version and records the action in publish history.</p>
        </div>
      </section>

      <section v-else-if="activeTab === 'versions'" class="panel">
        <div class="section-heading">
          <div><h2>Versions</h2><p>Review every stored revision and explicitly choose which one is published.</p></div>
        </div>

        <div v-if="lifecycle.versions.length" class="table-wrap">
          <table>
            <thead><tr><th>Version</th><th>Status</th><th>Change notes</th><th>Published</th><th>Created</th><th v-if="canManage">Action</th></tr></thead>
            <tbody>
              <tr v-for="version in lifecycle.versions" :key="version.id">
                <td><strong>Version {{ version.version }}</strong><small v-if="version.isLatest">Current / latest</small></td>
                <td><span class="version-status" :class="statusLabel(version).toLowerCase()">{{ statusLabel(version) }}</span></td>
                <td>{{ version.changelog || '—' }}</td>
                <td>{{ formatDate(version.publishedAt) }}</td>
                <td>{{ formatDate(version.createdAt) }}</td>
                <td v-if="canManage" class="actions-cell">
                  <button
                    type="button"
                    class="btn primary small"
                    :disabled="lifecycle.saving || version.isPublished || !lifecycle.form.isActive"
                    :aria-label="`Publish version ${version.version}`"
                    @click="publish(version)"
                  >
                    {{ version.isPublished ? 'Published' : 'Publish' }}
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="empty">No stored versions were returned for this form.</div>
      </section>

      <section v-else-if="activeTab === 'media'" class="panel">
        <FormMediaPanel
          :form-id="formId"
          :versions="lifecycle.versions"
          :current-version-id="lifecycle.form.formVersionId"
          :can-manage="canManage"
        />
      </section>

      <section v-else-if="activeTab === 'external-data'" class="panel">
        <FormExternalDataPanel
          :form-id="formId"
          :versions="lifecycle.versions"
          :current-version-id="lifecycle.form.formVersionId"
          :can-manage="canManage"
        />
      </section>

      <section v-else-if="activeTab === 'history'" class="panel">
        <div class="section-heading">
          <div><h2>Publish history</h2><p>Trace publishing and archival lifecycle actions for this form.</p></div>
        </div>

        <div v-if="lifecycle.history.length" class="history-list">
          <article v-for="entry in lifecycle.history" :key="entry.id" class="history-entry">
            <div class="history-marker" aria-hidden="true"></div>
            <div class="history-content">
              <div class="history-heading">
                <strong>{{ entry.action }}</strong>
                <span>Version {{ entry.version }}</span>
                <time>{{ formatDate(entry.performedAt) }}</time>
              </div>
              <p>By {{ entry.performedByName || entry.performedByUsername }}</p>
              <p v-if="entry.previousVersion" class="muted">Previous version: {{ entry.previousVersion }}</p>
              <p v-if="entry.notes" class="notes">{{ entry.notes }}</p>
            </div>
          </article>
        </div>
        <div v-else class="empty">No publish lifecycle actions have been recorded yet.</div>
      </section>

      <section v-else-if="activeTab === 'mobile-access'" class="panel">
        <MobileFormAssignmentsPanel :project-id="projectId" :form-id="formId" />
      </section>

      <section v-else class="panel">
        <FormAssignmentsPanel :project-id="projectId" :form-id="formId" />
      </section>
    </template>
  </main>
</template>

<style scoped>
.form-detail-page { max-width: 1220px; margin: 0 auto; padding: 28px 24px 56px; color: #111827; }
.back-link { border: 0; background: transparent; padding: 0; color: #2563eb; font: inherit; font-weight: 600; cursor: pointer; margin-bottom: 20px; }
.page-header { display: flex; justify-content: space-between; gap: 24px; align-items: flex-start; margin-bottom: 20px; }
.eyebrow { color: #6b7280; font-size: 13px; font-weight: 700; text-transform: uppercase; letter-spacing: .08em; margin-bottom: 6px; }
.title-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
h1, h2, h3, p { margin-top: 0; } h1 { margin-bottom: 4px; font-size: 30px; } h2 { font-size: 20px; margin-bottom: 6px; } h3 { font-size: 16px; }
.description { color: #4b5563; max-width: 760px; margin: 8px 0; }.identity { color: #6b7280; font-size: 13px; margin: 8px 0 0; }.identity code { color: #374151; }
.status-pill, .version-status { display: inline-flex; align-items: center; border-radius: 999px; padding: 4px 9px; font-size: 12px; font-weight: 700; }
.status-pill.published, .version-status.published { background: #ecfdf5; color: #047857; }.status-pill.draft, .version-status.validated, .version-status.draft { background: #eff6ff; color: #1d4ed8; }.status-pill.archived, .version-status.archived, .version-status.deprecated { background: #f3f4f6; color: #4b5563; }
.header-actions { display: flex; gap: 10px; }.btn { border: 1px solid transparent; border-radius: 7px; padding: 9px 13px; font: inherit; font-weight: 650; cursor: pointer; }.btn:disabled { opacity: .5; cursor: not-allowed; }.btn.primary { background: #2563eb; color: white; }.btn.secondary { background: white; border-color: #d1d5db; color: #374151; }.btn.danger { background: #fff1f2; border-color: #fecdd3; color: #be123c; }.btn.small { padding: 7px 10px; font-size: 13px; }
.notice { border-radius: 8px; padding: 10px 12px; margin-bottom: 16px; }.notice.success { background: #ecfdf5; color: #065f46; }.notice.error, .error-card { background: #fff1f2; color: #9f1239; }
.tabs { display: flex; gap: 4px; border-bottom: 1px solid #e5e7eb; margin-bottom: 20px; overflow-x: auto; }.tabs button { border: 0; border-bottom: 2px solid transparent; background: transparent; padding: 11px 14px; color: #6b7280; font: inherit; font-weight: 650; cursor: pointer; white-space: nowrap; }.tabs button.active { color: #1d4ed8; border-bottom-color: #2563eb; }
.panel, .state-card { background: white; border: 1px solid #e5e7eb; border-radius: 11px; padding: 20px; }.state-card { display: grid; gap: 10px; }
.summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin: 16px 0 20px; }.summary-card { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 9px; padding: 14px; display: grid; gap: 6px; }.summary-card span { color: #6b7280; font-size: 13px; }.summary-card strong { font-size: 18px; }.summary-card .small-value { font-size: 13px; overflow-wrap: anywhere; }
.guidance { border-left: 3px solid #60a5fa; background: #eff6ff; border-radius: 5px; padding: 14px 16px; }.guidance p { color: #374151; margin-bottom: 0; }
.section-heading { display: flex; justify-content: space-between; gap: 14px; margin-bottom: 14px; }.section-heading p { color: #6b7280; margin-bottom: 0; }
.table-wrap { overflow-x: auto; }table { width: 100%; border-collapse: collapse; min-width: 850px; }th, td { padding: 12px 10px; border-bottom: 1px solid #e5e7eb; text-align: left; vertical-align: top; font-size: 14px; }th { color: #6b7280; font-size: 12px; text-transform: uppercase; letter-spacing: .04em; }td small { display: block; color: #6b7280; margin-top: 4px; }.actions-cell { width: 110px; }
.history-list { display: grid; gap: 0; }.history-entry { display: grid; grid-template-columns: 16px 1fr; gap: 12px; padding: 4px 0 18px; }.history-marker { width: 10px; height: 10px; border-radius: 50%; background: #2563eb; margin-top: 5px; box-shadow: 0 0 0 4px #dbeafe; }.history-content { border-bottom: 1px solid #e5e7eb; padding-bottom: 16px; }.history-heading { display: flex; flex-wrap: wrap; gap: 8px 14px; align-items: baseline; }.history-heading strong { color: #1d4ed8; }.history-heading span, .history-heading time { color: #6b7280; font-size: 13px; }.history-content p { margin: 6px 0 0; }.muted { color: #6b7280; font-size: 13px; }.notes { color: #374151; }.empty { color: #6b7280; padding: 24px 0; text-align: center; }
@media (max-width: 900px) { .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.page-header { flex-direction: column; } }
@media (max-width: 560px) { .form-detail-page { padding: 20px 14px 40px; }.summary-grid { grid-template-columns: 1fr; } h1 { font-size: 25px; } }
</style>
