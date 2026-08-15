<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useAuditStore } from '@/stores/audit'

const auditStore = useAuditStore()
const expanded = ref<string | null>(null)
const filters = reactive({
  action: '',
  resourceType: '',
  status: '',
  actor: '',
  ipAddress: '',
  from: '',
  to: ''
})

const hasPrevious = computed(() => auditStore.page > 0)
const hasNext = computed(() => auditStore.page + 1 < auditStore.totalPages)

function iso(value: string) {
  if (!value) return undefined
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString()
}

async function load(page = 0) {
  await auditStore.fetchLogs({
    action: filters.action,
    resourceType: filters.resourceType,
    status: filters.status,
    actor: filters.actor,
    ipAddress: filters.ipAddress,
    from: iso(filters.from),
    to: iso(filters.to),
    page,
    size: auditStore.size || 50
  })
}

function resetFilters() {
  filters.action = ''
  filters.resourceType = ''
  filters.status = ''
  filters.actor = ''
  filters.ipAddress = ''
  filters.from = ''
  filters.to = ''
  void load(0)
}

function target(entry: any) {
  return entry.resourceName || entry.resourceId || '—'
}

function formatDate(value?: string | null) {
  if (!value) return '—'
  return new Date(value).toLocaleString()
}

onMounted(() => void load())
</script>

<template>
  <section class="audit-panel">
    <header class="panel-header">
      <div>
        <h2>System Audit Trail</h2>
        <p>Search security and administrative events by action, actor, target, status, address, or time.</p>
      </div>
      <span class="count">{{ auditStore.totalElements }} events</span>
    </header>

    <form class="filters" @submit.prevent="load(0)">
      <label>
        Action
        <input v-model="filters.action" data-testid="audit-action" placeholder="FORM_PUBLISHED" />
      </label>
      <label>
        Target type
        <input v-model="filters.resourceType" data-testid="audit-resource-type" placeholder="FORM" />
      </label>
      <label>
        Status
        <select v-model="filters.status" data-testid="audit-status">
          <option value="">All</option>
          <option value="SUCCESS">Success</option>
          <option value="ERROR">Error</option>
        </select>
      </label>
      <label>
        Actor
        <input v-model="filters.actor" data-testid="audit-actor" placeholder="username" />
      </label>
      <label>
        IP address
        <input v-model="filters.ipAddress" data-testid="audit-ip" placeholder="203.0.113.10" />
      </label>
      <label>
        From
        <input v-model="filters.from" data-testid="audit-from" type="datetime-local" />
      </label>
      <label>
        To
        <input v-model="filters.to" data-testid="audit-to" type="datetime-local" />
      </label>
      <div class="filter-actions">
        <button class="btn btn-primary" type="submit">Apply filters</button>
        <button class="btn btn-secondary" type="button" @click="resetFilters">Reset</button>
      </div>
    </form>

    <p v-if="auditStore.error" class="error">{{ auditStore.error }}</p>
    <div v-if="auditStore.loading" class="state">Loading audit trail…</div>
    <div v-else-if="auditStore.entries.length === 0" class="state">No audit events match the current filters.</div>

    <div v-else class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Time</th>
            <th>Actor</th>
            <th>Action</th>
            <th>Target</th>
            <th>Status</th>
            <th>IP</th>
            <th>User agent</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <template v-for="entry in auditStore.entries" :key="entry.id">
            <tr>
              <td>{{ formatDate(entry.createdAt) }}</td>
              <td>{{ entry.actorName || 'system' }} <small>{{ entry.actorType }}</small></td>
              <td><code>{{ entry.action }}</code></td>
              <td>{{ target(entry) }} <small v-if="entry.resourceType">{{ entry.resourceType }}</small></td>
              <td><span class="status" :class="entry.status.toLowerCase()">{{ entry.status }}</span></td>
              <td>{{ entry.ipAddress || '—' }}</td>
              <td class="agent">{{ entry.userAgent || '—' }}</td>
              <td>
                <button class="link-button" type="button" @click="expanded = expanded === entry.id ? null : entry.id">
                  {{ expanded === entry.id ? 'Hide' : 'Details' }}
                </button>
              </td>
            </tr>
            <tr v-if="expanded === entry.id" class="detail-row">
              <td colspan="8">
                <div class="detail-grid">
                  <div><strong>Resource ID</strong><span>{{ entry.resourceId || '—' }}</span></div>
                  <div><strong>Request ID</strong><span>{{ entry.requestId || '—' }}</span></div>
                  <div><strong>Session ID</strong><span>{{ entry.sessionId || '—' }}</span></div>
                  <div v-if="entry.errorMessage"><strong>Error</strong><span>{{ entry.errorMessage }}</span></div>
                </div>
                <div v-if="entry.details?.length" class="change-list">
                  <h4>Field changes</h4>
                  <div v-for="detail in entry.details" :key="detail.id" class="change">
                    <strong>{{ detail.key }}</strong>
                    <span>{{ detail.oldValue ?? '—' }} → {{ detail.newValue ?? '—' }}</span>
                  </div>
                </div>
                <details v-if="entry.oldValues || entry.newValues || entry.metadata">
                  <summary>Stored event payload</summary>
                  <pre v-if="entry.oldValues">Old: {{ entry.oldValues }}</pre>
                  <pre v-if="entry.newValues">New: {{ entry.newValues }}</pre>
                  <pre v-if="entry.metadata">Metadata: {{ entry.metadata }}</pre>
                </details>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <footer class="pagination">
      <button class="btn btn-secondary" :disabled="!hasPrevious || auditStore.loading" @click="load(auditStore.page - 1)">Previous</button>
      <span>Page {{ auditStore.totalPages ? auditStore.page + 1 : 0 }} of {{ auditStore.totalPages }}</span>
      <button class="btn btn-secondary" :disabled="!hasNext || auditStore.loading" @click="load(auditStore.page + 1)">Next</button>
    </footer>
  </section>
</template>

<style scoped>
.audit-panel { display: grid; gap: 20px; }
.panel-header { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; }
.panel-header h2 { margin: 0 0 6px; }
.panel-header p { margin: 0; color: #6b7280; }
.count { white-space: nowrap; color: #6b7280; }
.filters { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; align-items: end; }
.filters label { display: grid; gap: 5px; font-size: 13px; color: #374151; }
.filters input, .filters select { min-height: 38px; border: 1px solid #d1d5db; border-radius: 6px; padding: 7px 9px; background: white; }
.filter-actions { display: flex; gap: 8px; }
.table-wrap { overflow-x: auto; border: 1px solid #e5e7eb; border-radius: 8px; }
table { width: 100%; border-collapse: collapse; min-width: 1000px; }
th, td { padding: 11px 12px; text-align: left; border-bottom: 1px solid #e5e7eb; vertical-align: top; font-size: 13px; }
th { background: #f9fafb; color: #374151; font-weight: 600; }
td small { display: block; margin-top: 3px; color: #6b7280; }
.agent { max-width: 240px; overflow-wrap: anywhere; }
.status { font-size: 12px; font-weight: 600; }
.status.success { color: #166534; }
.status.error, .status.failed, .status.failure { color: #b91c1c; }
.link-button { border: 0; background: transparent; cursor: pointer; text-decoration: underline; }
.detail-row td { background: #f9fafb; }
.detail-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px; }
.detail-grid div { display: grid; gap: 4px; }
.change-list { margin-top: 14px; }
.change { display: flex; gap: 14px; padding: 5px 0; }
pre { white-space: pre-wrap; overflow-wrap: anywhere; }
.state { padding: 24px; border: 1px dashed #d1d5db; border-radius: 8px; color: #6b7280; text-align: center; }
.error { color: #b91c1c; }
.pagination { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
</style>
