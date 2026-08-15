<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useFormStore } from '@/stores/form'
import {
  useReportStore,
  type AvailableReportField,
  type ReportAggregation,
  type ReportDefinitionInput,
  type ReportFieldInput,
  type ReportFilterInput,
  type ReportOperator
} from '@/stores/report'

const props = defineProps<{ projectId: string }>()
const reportStore = useReportStore()
const formStore = useFormStore()

const selectedReportId = ref<string | null>(null)
const saving = ref(false)
const notice = ref('')
const runFrom = ref('')
const runTo = ref('')

const draft = reactive<ReportDefinitionInput>({
  name: '',
  description: '',
  formId: '',
  reportType: 'TABLE',
  active: true,
  fields: [],
  filters: []
})

const aggregations: ReportAggregation[] = ['NONE', 'COUNT', 'SUM', 'AVG', 'MIN', 'MAX']
const operators: ReportOperator[] = ['EQ', 'NE', 'CONTAINS', 'GT', 'GTE', 'LT', 'LTE', 'IN', 'BETWEEN', 'IS_EMPTY', 'NOT_EMPTY']

const selectedFieldKeys = computed(() => new Set(draft.fields.map(field => key(field.fieldKind, field.fieldPath))))
const canSave = computed(() => draft.name.trim().length > 0 && draft.formId.length > 0 && draft.fields.length > 0)

onMounted(async () => {
  await Promise.all([
    reportStore.fetchDefinitions(props.projectId),
    formStore.forms.length ? Promise.resolve(formStore.forms) : formStore.fetchForms(props.projectId)
  ])
})

function key(kind: string, path: string) {
  return `${kind}:${path}`
}

function resetDraft() {
  selectedReportId.value = null
  Object.assign(draft, {
    name: '', description: '', formId: '', reportType: 'TABLE', active: true, fields: [], filters: []
  })
  reportStore.availableFields = []
  reportStore.clearResult()
  notice.value = ''
}

async function selectReport(reportId: string) {
  const report = await reportStore.fetchDefinition(props.projectId, reportId)
  selectedReportId.value = report.id
  Object.assign(draft, {
    name: report.name,
    description: report.description || '',
    formId: report.formId,
    reportType: report.reportType,
    active: report.active,
    fields: report.fields.map(field => ({ ...field })),
    filters: report.filters.map(filter => ({ ...filter }))
  })
  await reportStore.discoverFields(props.projectId, report.formId)
  reportStore.clearResult()
}

async function formChanged() {
  draft.fields = []
  draft.filters = []
  reportStore.clearResult()
  if (draft.formId) await reportStore.discoverFields(props.projectId, draft.formId)
  else reportStore.availableFields = []
}

function toggleField(field: AvailableReportField) {
  const fieldKey = key(field.fieldKind, field.fieldPath)
  const index = draft.fields.findIndex(item => key(item.fieldKind, item.fieldPath) === fieldKey)
  if (index >= 0) {
    draft.fields.splice(index, 1)
    draft.filters = (draft.filters || []).filter(filter => key(filter.fieldKind, filter.fieldPath) !== fieldKey)
    return
  }
  draft.fields.push({
    fieldPath: field.fieldPath,
    fieldKind: field.fieldKind,
    label: field.label,
    dataType: field.dataType,
    aggregation: 'NONE',
    groupBy: false,
    sortOrder: draft.fields.length
  })
}

function selectedField(path: string, kind: string): ReportFieldInput | undefined {
  return draft.fields.find(field => key(field.fieldKind, field.fieldPath) === key(kind, path))
}

function addFilter() {
  const field = draft.fields[0]
  if (!field) return
  ;(draft.filters ||= []).push({
    fieldPath: field.fieldPath,
    fieldKind: field.fieldKind,
    operator: 'EQ',
    value: '',
    sortOrder: draft.filters?.length || 0
  })
}

function removeFilter(index: number) {
  draft.filters?.splice(index, 1)
}

function changeFilterField(filter: ReportFilterInput, encoded: string) {
  const [kind, ...pathParts] = encoded.split(':')
  filter.fieldKind = kind as 'ANSWER' | 'SYSTEM'
  filter.fieldPath = pathParts.join(':')
}

async function saveReport() {
  if (!canSave.value) return
  saving.value = true
  notice.value = ''
  try {
    const payload: ReportDefinitionInput = {
      ...draft,
      fields: draft.fields.map((field, index) => ({ ...field, sortOrder: index })),
      filters: (draft.filters || []).map((filter, index) => ({ ...filter, sortOrder: index }))
    }
    const saved = selectedReportId.value
      ? await reportStore.updateDefinition(props.projectId, selectedReportId.value, payload)
      : await reportStore.createDefinition(props.projectId, payload)
    selectedReportId.value = saved.id
    notice.value = 'Report definition saved.'
  } finally {
    saving.value = false
  }
}

async function runReport() {
  if (!selectedReportId.value) {
    await saveReport()
  }
  if (!selectedReportId.value) return
  await reportStore.runReport(props.projectId, selectedReportId.value, {
    from: runFrom.value ? new Date(runFrom.value).toISOString() : undefined,
    to: runTo.value ? new Date(`${runTo.value}T23:59:59.999`).toISOString() : undefined
  })
}

async function exportReport(format: 'csv' | 'xlsx') {
  if (!selectedReportId.value) return
  const { blob, filename } = await reportStore.exportReport(props.projectId, selectedReportId.value, format, {
    from: runFrom.value ? new Date(runFrom.value).toISOString() : undefined,
    to: runTo.value ? new Date(`${runTo.value}T23:59:59.999`).toISOString() : undefined
  })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

async function deleteReport() {
  if (!selectedReportId.value) return
  if (!confirm('Delete this report definition?')) return
  await reportStore.deleteDefinition(props.projectId, selectedReportId.value)
  resetDraft()
}

function displayValue(value: unknown) {
  if (Array.isArray(value)) return value.join(' | ')
  return value == null ? '' : String(value)
}
</script>

<template>
  <section class="report-builder">
    <header class="builder-header">
      <div>
        <h2>Generic Reports</h2>
        <p>Build reports from any form without hard-coded domain fields.</p>
      </div>
      <button class="btn btn-primary" @click="resetDraft">New Report</button>
    </header>

    <div class="builder-layout">
      <aside class="report-list">
        <h3>Saved reports</h3>
        <button
          v-for="report in reportStore.definitions"
          :key="report.id"
          class="report-list-item"
          :class="{ active: selectedReportId === report.id }"
          @click="selectReport(report.id)"
        >
          <strong>{{ report.name }}</strong>
          <small>{{ report.reportType }}</small>
        </button>
        <p v-if="!reportStore.definitions.length" class="muted">No reports yet.</p>
      </aside>

      <div class="editor">
        <div class="card definition-card">
          <h3>Report definition</h3>
          <div class="form-grid">
            <label>
              Report name
              <input v-model="draft.name" data-testid="report-name" placeholder="e.g. Submission summary" />
            </label>
            <label>
              Source form
              <select v-model="draft.formId" data-testid="source-form" @change="formChanged">
                <option value="">Select a form</option>
                <option v-for="form in formStore.forms" :key="form.id" :value="form.id">{{ form.name }}</option>
              </select>
            </label>
            <label>
              Report type
              <select v-model="draft.reportType">
                <option value="TABLE">Table</option>
                <option value="SUMMARY">Summary</option>
              </select>
            </label>
            <label class="wide">
              Description
              <textarea v-model="draft.description" rows="2" />
            </label>
          </div>
        </div>

        <div v-if="draft.formId" class="card fields-card">
          <div class="section-heading">
            <div>
              <h3>Fields, groupings and summaries</h3>
              <p>Select answer or submission fields. Repeat indexes are handled generically.</p>
            </div>
          </div>
          <div class="field-table-wrap">
            <table class="field-table">
              <thead><tr><th>Use</th><th>Field</th><th>Type</th><th>Group</th><th>Aggregation</th></tr></thead>
              <tbody>
                <tr v-for="field in reportStore.availableFields" :key="key(field.fieldKind, field.fieldPath)">
                  <td><input type="checkbox" :checked="selectedFieldKeys.has(key(field.fieldKind, field.fieldPath))" @change="toggleField(field)" /></td>
                  <td><strong>{{ field.label }}</strong><small>{{ field.fieldPath }}</small></td>
                  <td>{{ field.dataType }}</td>
                  <td><input v-if="selectedField(field.fieldPath, field.fieldKind)" v-model="selectedField(field.fieldPath, field.fieldKind)!.groupBy" type="checkbox" /></td>
                  <td>
                    <select v-if="selectedField(field.fieldPath, field.fieldKind)" v-model="selectedField(field.fieldPath, field.fieldKind)!.aggregation">
                      <option v-for="aggregation in aggregations" :key="aggregation" :value="aggregation">{{ aggregation }}</option>
                    </select>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="draft.fields.length" class="card filters-card">
          <div class="section-heading">
            <h3>Saved filters</h3>
            <button class="btn btn-secondary" @click="addFilter">Add Filter</button>
          </div>
          <div v-for="(filter, index) in draft.filters" :key="index" class="filter-row">
            <select :value="key(filter.fieldKind, filter.fieldPath)" @change="changeFilterField(filter, ($event.target as HTMLSelectElement).value)">
              <option v-for="field in draft.fields" :key="key(field.fieldKind, field.fieldPath)" :value="key(field.fieldKind, field.fieldPath)">
                {{ field.label || field.fieldPath }}
              </option>
            </select>
            <select v-model="filter.operator">
              <option v-for="operator in operators" :key="operator" :value="operator">{{ operator }}</option>
            </select>
            <input v-if="!['IS_EMPTY', 'NOT_EMPTY'].includes(filter.operator)" v-model="filter.value" placeholder="Value" />
            <input v-if="filter.operator === 'BETWEEN'" v-model="filter.valueTo" placeholder="To" />
            <button class="icon-button" title="Remove filter" @click="removeFilter(index)">×</button>
          </div>
        </div>

        <div class="actions">
          <span v-if="notice" class="success">{{ notice }}</span>
          <button v-if="selectedReportId" class="btn btn-danger" @click="deleteReport">Delete</button>
          <button class="btn btn-secondary" :disabled="!canSave || saving" @click="saveReport">{{ saving ? 'Saving…' : 'Save Report' }}</button>
          <button class="btn btn-primary" :disabled="!canSave" @click="runReport">Run Report</button>
        </div>

        <div v-if="selectedReportId" class="card run-card">
          <div class="run-filters">
            <label>From <input v-model="runFrom" type="date" /></label>
            <label>To <input v-model="runTo" type="date" /></label>
            <button class="btn btn-secondary" @click="runReport">Refresh</button>
            <button class="btn btn-secondary" @click="exportReport('csv')">Export CSV</button>
            <button class="btn btn-secondary" @click="exportReport('xlsx')">Export Excel</button>
          </div>
        </div>

        <div v-if="reportStore.result" class="card results-card">
          <div class="section-heading">
            <div>
              <h3>{{ reportStore.result.reportName }}</h3>
              <p>{{ reportStore.result.rowCount }} result rows from {{ reportStore.result.sourceSubmissionCount }} matching submissions</p>
            </div>
          </div>
          <div class="result-scroll">
            <table class="result-table">
              <thead><tr><th v-for="column in reportStore.result.columns" :key="column.key">{{ column.label }}</th></tr></thead>
              <tbody>
                <tr v-for="(row, rowIndex) in reportStore.result.rows" :key="rowIndex">
                  <td v-for="column in reportStore.result.columns" :key="column.key">{{ displayValue(row[column.key]) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <p v-if="reportStore.error" class="error">{{ reportStore.error }}</p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.report-builder { display: grid; gap: 20px; }
.builder-header, .section-heading, .actions, .run-filters { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.builder-header h2, .card h3 { margin: 0; }
.builder-header p, .section-heading p, .results-card p { margin: 4px 0 0; color: #6b7280; }
.builder-layout { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 20px; }
.report-list { display: flex; flex-direction: column; gap: 8px; }
.report-list h3 { margin-top: 0; }
.report-list-item { text-align: left; border: 1px solid #e5e7eb; background: white; border-radius: 8px; padding: 10px 12px; display: grid; gap: 3px; cursor: pointer; }
.report-list-item.active { border-color: #2563eb; box-shadow: 0 0 0 1px #2563eb; }
.report-list-item small, .field-table small { display: block; color: #6b7280; font-size: 11px; overflow-wrap: anywhere; }
.editor { min-width: 0; display: grid; gap: 16px; }
.card { border: 1px solid #e5e7eb; border-radius: 10px; background: #fff; padding: 16px; }
.form-grid { margin-top: 14px; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.form-grid label, .run-filters label { display: grid; gap: 6px; font-size: 13px; color: #374151; }
.form-grid .wide { grid-column: 1 / -1; }
input, select, textarea { width: 100%; box-sizing: border-box; border: 1px solid #d1d5db; border-radius: 7px; padding: 8px 10px; font: inherit; background: white; }
input[type='checkbox'] { width: auto; }
.field-table-wrap, .result-scroll { overflow-x: auto; margin-top: 12px; }
.field-table, .result-table { width: 100%; border-collapse: collapse; min-width: 640px; }
th, td { border-bottom: 1px solid #e5e7eb; padding: 9px 10px; text-align: left; vertical-align: top; font-size: 13px; }
th { background: #f9fafb; color: #374151; }
.filter-row { display: grid; grid-template-columns: minmax(180px, 2fr) 120px minmax(120px, 1fr) minmax(120px, 1fr) 36px; gap: 8px; margin-top: 10px; align-items: center; }
.icon-button { border: 0; background: transparent; font-size: 22px; cursor: pointer; }
.btn { border: 1px solid transparent; border-radius: 7px; padding: 8px 12px; cursor: pointer; }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.btn-primary { background: #2563eb; color: white; }
.btn-secondary { background: white; border-color: #d1d5db; color: #374151; }
.btn-danger { background: #fff; border-color: #fecaca; color: #b91c1c; }
.success { color: #166534; margin-right: auto; }
.error { color: #b91c1c; }
.muted { color: #6b7280; }
@media (max-width: 800px) {
  .builder-layout { grid-template-columns: 1fr; }
  .report-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); }
  .form-grid { grid-template-columns: 1fr; }
  .form-grid .wide { grid-column: auto; }
  .filter-row { grid-template-columns: 1fr 1fr; }
  .filter-row .icon-button { grid-column: 2; }
}
</style>
