import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import api from '@/services/api'

export type ProjectSettingScalar = string | number | boolean | string[] | null

export interface ProjectSettingValue {
  value: ProjectSettingScalar
  description?: string
  [key: string]: unknown
}

export type ProjectSettingsMap = Record<string, ProjectSettingValue>

export interface ProjectSettingResponse {
  settingKey: string
  settingValue: ProjectSettingValue
  description?: string | null
}

function normalizeSettingValue(raw: unknown): ProjectSettingValue {
  if (
    raw !== null &&
    typeof raw === 'object' &&
    !Array.isArray(raw) &&
    Object.prototype.hasOwnProperty.call(raw, 'value')
  ) {
    return { ...(raw as Record<string, unknown>) } as ProjectSettingValue
  }

  return { value: raw as ProjectSettingScalar }
}

export const useProjectSettingsStore = defineStore('projectSettings', () => {
  const settings = ref<ProjectSettingsMap>({})
  const loading = ref(false)
  const savingKey = ref<string | null>(null)
  const error = ref<string | null>(null)

  const isEmpty = computed(() => Object.keys(settings.value).length === 0)

  async function fetchSettings(projectId: string): Promise<ProjectSettingsMap> {
    loading.value = true
    error.value = null

    try {
      const response = await api.get<Record<string, unknown>>(
        `/projects/${projectId}/settings`,
      )

      settings.value = Object.fromEntries(
        Object.entries(response.data ?? {}).map(([key, value]) => [
          key,
          normalizeSettingValue(value),
        ]),
      )

      return settings.value
    } catch (err: any) {
      error.value =
        err.response?.data?.message || 'Failed to load project settings'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function initializeDefaults(projectId: string): Promise<ProjectSettingsMap> {
    loading.value = true
    error.value = null

    try {
      await api.post(`/projects/${projectId}/settings/initialize`)
      return await fetchSettings(projectId)
    } catch (err: any) {
      error.value =
        err.response?.data?.message || 'Failed to initialize project settings'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function updateSetting(
    projectId: string,
    settingKey: string,
    value: ProjectSettingScalar,
    description?: string,
  ): Promise<ProjectSettingValue> {
    savingKey.value = settingKey
    error.value = null

    try {
      const existing = settings.value[settingKey] ?? { value: null }
      const effectiveDescription =
        description ?? (typeof existing.description === 'string'
          ? existing.description
          : undefined)

      const settingValue: ProjectSettingValue = {
        ...existing,
        value,
      }

      if (effectiveDescription) {
        settingValue.description = effectiveDescription
      }

      const response = await api.put<ProjectSettingResponse>(
        `/projects/${projectId}/settings/${encodeURIComponent(settingKey)}`,
        {
          value: settingValue,
          description: effectiveDescription,
        },
      )

      const normalized = normalizeSettingValue(
        response.data?.settingValue ?? settingValue,
      )

      if (
        response.data?.description &&
        typeof normalized.description !== 'string'
      ) {
        normalized.description = response.data.description
      }

      settings.value = {
        ...settings.value,
        [settingKey]: normalized,
      }

      return normalized
    } catch (err: any) {
      error.value =
        err.response?.data?.message || `Failed to update ${settingKey}`
      throw err
    } finally {
      savingKey.value = null
    }
  }

  return {
    settings,
    loading,
    savingKey,
    error,
    isEmpty,
    fetchSettings,
    initializeDefaults,
    updateSetting,
  }
})
