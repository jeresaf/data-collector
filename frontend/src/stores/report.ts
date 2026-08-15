import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '@/services/api'

export type ReportFieldKind = 'ANSWER' | 'SYSTEM'
export type ReportAggregation = 'NONE' | 'COUNT' | 'SUM' | 'AVG' | 'MIN' | 'MAX'
export type ReportOperator = 'EQ' | 'NE' | 'CONTAINS' | 'GT' | 'GTE' | 'LT' | 'LTE' | 'IN' | 'BETWEEN' | 'IS_EMPTY' | 'NOT_EMPTY'

export interface AvailableReportField {
  fieldPath: string
  label: string
  fieldKind: ReportFieldKind
  dataType: string
  media: boolean
  calculated: boolean
}

export interface ReportFieldInput {
  fieldPath: string
  fieldKind: ReportFieldKind
  label?: string
  dataType?: string
  aggregation?: ReportAggregation
  groupBy?: boolean
  sortOrder?: number
}

export interface ReportFilterInput {
  fieldPath: string
  fieldKind: ReportFieldKind
  operator: ReportOperator
  value?: string
  valueTo?: string
  sortOrder?: number
}

export interface ReportDefinition {
  id: string
  projectId: string
  formId: string
  name: string
  description?: string | null
  reportType: 'TABLE' | 'SUMMARY'
  active: boolean
  fields: Array<ReportFieldInput & { id?: string }>
  filters: Array<ReportFilterInput & { id?: string }>
  createdAt?: string
  updatedAt?: string
}

export interface ReportDefinitionInput {
  name: string
  description?: string
  formId: string
  reportType?: 'TABLE' | 'SUMMARY'
  active?: boolean
  fields: ReportFieldInput[]
  filters?: ReportFilterInput[]
}

export interface RunParameters {
  from?: string
  to?: string
  formVersionId?: string
  submitterId?: string
  sourceClient?: string
  filters?: ReportFilterInput[]
}

export interface ReportColumn {
  key: string
  label: string
  dataType?: string
  fieldKind: ReportFieldKind
  aggregation: ReportAggregation
  groupBy: boolean
}

export interface ReportResult {
  runId: string
  reportId: string
  reportName: string
  columns: ReportColumn[]
  rows: Array<Record<string, unknown>>
  rowCount: number
  sourceSubmissionCount: number
  executedAt: string
}

export interface DashboardWidget {
  id: string
  projectId: string
  reportDefinitionId?: string | null
  title: string
  widgetType: 'TABLE' | 'NUMBER' | 'BAR' | 'LINE' | 'PIE'
  config?: string | null
  sortOrder: number
  active: boolean
}

export const useReportStore = defineStore('report', () => {
  const definitions = ref<ReportDefinition[]>([])
  const availableFields = ref<AvailableReportField[]>([])
  const result = ref<ReportResult | null>(null)
  const widgets = ref<DashboardWidget[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function fetchDefinitions(projectId: string) {
    loading.value = true
    error.value = null
    try {
      const response = await api.get<ReportDefinition[]>(`/projects/${projectId}/reports`)
      definitions.value = response.data
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to load reports'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function fetchDefinition(projectId: string, reportId: string) {
    const response = await api.get<ReportDefinition>(`/projects/${projectId}/reports/${reportId}`)
    const index = definitions.value.findIndex(report => report.id === reportId)
    if (index >= 0) definitions.value[index] = response.data
    return response.data
  }

  async function discoverFields(projectId: string, formId: string) {
    const response = await api.get<AvailableReportField[]>(`/projects/${projectId}/reports/forms/${formId}/fields`)
    availableFields.value = response.data
    return response.data
  }

  async function createDefinition(projectId: string, input: ReportDefinitionInput) {
    const response = await api.post<ReportDefinition>(`/projects/${projectId}/reports`, input)
    definitions.value = [...definitions.value, response.data].sort((a, b) => a.name.localeCompare(b.name))
    return response.data
  }

  async function updateDefinition(projectId: string, reportId: string, input: ReportDefinitionInput) {
    const response = await api.put<ReportDefinition>(`/projects/${projectId}/reports/${reportId}`, input)
    const index = definitions.value.findIndex(report => report.id === reportId)
    if (index >= 0) definitions.value[index] = response.data
    return response.data
  }

  async function deleteDefinition(projectId: string, reportId: string) {
    await api.delete(`/projects/${projectId}/reports/${reportId}`)
    definitions.value = definitions.value.filter(report => report.id !== reportId)
    if (result.value?.reportId === reportId) result.value = null
  }

  async function runReport(projectId: string, reportId: string, parameters: RunParameters = {}) {
    loading.value = true
    error.value = null
    try {
      const response = await api.post<ReportResult>(`/projects/${projectId}/reports/${reportId}/run`, parameters)
      result.value = response.data
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to run report'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function exportReport(projectId: string, reportId: string, format: 'csv' | 'xlsx', parameters: RunParameters = {}) {
    const response = await api.post(`/projects/${projectId}/reports/${reportId}/export/${format}`, parameters, {
      responseType: 'blob'
    })
    const disposition = String(response.headers?.['content-disposition'] || '')
    const match = disposition.match(/filename\*?=(?:UTF-8''|\")?([^\";]+)/i)
    return {
      blob: response.data as Blob,
      filename: decodeURIComponent(match?.[1] || `report.${format}`)
    }
  }

  async function fetchWidgets(projectId: string) {
    const response = await api.get<DashboardWidget[]>(`/projects/${projectId}/reports/widgets`)
    widgets.value = response.data
    return response.data
  }

  function clearResult() {
    result.value = null
  }

  return {
    definitions,
    availableFields,
    result,
    widgets,
    loading,
    error,
    fetchDefinitions,
    fetchDefinition,
    discoverFields,
    createDefinition,
    updateDefinition,
    deleteDefinition,
    runReport,
    exportReport,
    fetchWidgets,
    clearResult
  }
})
