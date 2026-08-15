import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import api from '@/services/api'

export interface ReferenceList {
  id: string
  name: string
  displayName?: string | null
  description?: string | null
  active: boolean
  itemCount: number
  createdAt?: string
  updatedAt?: string
}

export interface ReferenceRelationship {
  id: string
  parentItemId: string
  parentListId: string
  parentListName: string
  parentName: string
  parentLabel: string
  relationshipType: string
  sortOrder: number
}

export interface ReferenceItem {
  id: string
  name: string
  label: string
  sortOrder: number
  active: boolean
  properties: Record<string, string>
  parents: ReferenceRelationship[]
  createdAt?: string
  updatedAt?: string
}

export interface ReferenceListInput {
  name: string
  displayName?: string
  description?: string
  active?: boolean
}

export interface ReferenceItemInput {
  name: string
  label: string
  sortOrder?: number
  active?: boolean
  properties?: Record<string, string>
  parents?: Array<{ parentItemId: string; relationshipType?: string; sortOrder?: number }>
}

export interface ImportResult {
  created: number
  updated: number
  relationshipsCreated: number
  warnings: string[]
}

export const useReferenceDataStore = defineStore('referenceData', () => {
  const lists = ref<ReferenceList[]>([])
  const itemsByList = ref<Record<string, ReferenceItem[]>>({})
  const loading = ref(false)
  const error = ref<string | null>(null)

  const totalItems = computed(() => lists.value.reduce((sum, list) => sum + list.itemCount, 0))

  async function fetchLists(projectId: string) {
    loading.value = true
    error.value = null
    try {
      const response = await api.get<ReferenceList[]>(`/projects/${projectId}/reference-lists`)
      lists.value = response.data
      return response.data
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to load reference lists'
      throw err
    } finally {
      loading.value = false
    }
  }

  async function createList(projectId: string, input: ReferenceListInput) {
    const response = await api.post<ReferenceList>(`/projects/${projectId}/reference-lists`, input)
    lists.value = [...lists.value, response.data].sort((a, b) => a.name.localeCompare(b.name))
    itemsByList.value[response.data.id] = []
    return response.data
  }

  async function updateList(projectId: string, listId: string, input: Partial<ReferenceListInput>) {
    const response = await api.put<ReferenceList>(`/projects/${projectId}/reference-lists/${listId}`, input)
    const index = lists.value.findIndex(list => list.id === listId)
    if (index >= 0) lists.value[index] = response.data
    return response.data
  }

  async function deleteList(projectId: string, listId: string) {
    await api.delete(`/projects/${projectId}/reference-lists/${listId}`)
    lists.value = lists.value.filter(list => list.id !== listId)
    delete itemsByList.value[listId]
  }

  async function fetchItems(projectId: string, listId: string) {
    const response = await api.get<ReferenceItem[]>(`/projects/${projectId}/reference-lists/${listId}/items`)
    itemsByList.value[listId] = response.data
    const list = lists.value.find(entry => entry.id === listId)
    if (list) list.itemCount = response.data.length
    return response.data
  }

  async function createItem(projectId: string, listId: string, input: ReferenceItemInput) {
    const response = await api.post<ReferenceItem>(`/projects/${projectId}/reference-lists/${listId}/items`, input)
    itemsByList.value[listId] = [...(itemsByList.value[listId] || []), response.data]
      .sort((a, b) => a.sortOrder - b.sortOrder || a.label.localeCompare(b.label))
    const list = lists.value.find(entry => entry.id === listId)
    if (list) list.itemCount = itemsByList.value[listId].length
    return response.data
  }

  async function updateItem(projectId: string, listId: string, itemId: string, input: ReferenceItemInput) {
    const response = await api.put<ReferenceItem>(
      `/projects/${projectId}/reference-lists/${listId}/items/${itemId}`,
      input
    )
    const items = itemsByList.value[listId] || []
    const index = items.findIndex(item => item.id === itemId)
    if (index >= 0) items[index] = response.data
    itemsByList.value[listId] = [...items].sort((a, b) => a.sortOrder - b.sortOrder || a.label.localeCompare(b.label))
    return response.data
  }

  async function deleteItem(projectId: string, listId: string, itemId: string) {
    await api.delete(`/projects/${projectId}/reference-lists/${listId}/items/${itemId}`)
    itemsByList.value[listId] = (itemsByList.value[listId] || []).filter(item => item.id !== itemId)
    const list = lists.value.find(entry => entry.id === listId)
    if (list) list.itemCount = itemsByList.value[listId].length
  }

  async function importCsv(projectId: string, listId: string, file: File) {
    const formData = new FormData()
    formData.append('file', file)
    const response = await api.post<ImportResult>(
      `/projects/${projectId}/reference-lists/${listId}/import`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } }
    )
    await fetchItems(projectId, listId)
    return response.data
  }

  async function exportCsv(projectId: string, listId: string) {
    const response = await api.get(`/projects/${projectId}/reference-lists/${listId}/export`, {
      responseType: 'blob'
    })
    const disposition = String(response.headers?.['content-disposition'] || '')
    const match = disposition.match(/filename="?([^";]+)"?/i)
    return { blob: response.data as Blob, filename: match?.[1] || 'reference-data.csv' }
  }

  function clearError() {
    error.value = null
  }

  return {
    lists,
    itemsByList,
    totalItems,
    loading,
    error,
    fetchLists,
    createList,
    updateList,
    deleteList,
    fetchItems,
    createItem,
    updateItem,
    deleteItem,
    importCsv,
    exportCsv,
    clearError
  }
})
