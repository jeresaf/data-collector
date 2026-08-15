<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useReferenceDataStore, type ReferenceItem } from '@/stores/referenceData'

const props = defineProps<{ projectId: string }>()
const store = useReferenceDataStore()

const selectedListId = ref('')
const listDraft = reactive({ name: '', displayName: '', description: '' })
const editingItemId = ref<string | null>(null)
const showItemEditor = ref(false)
const importResult = ref<string | null>(null)
const itemDraft = reactive({
  name: '',
  label: '',
  sortOrder: 0,
  active: true,
  propertiesText: '',
  parentItemId: ''
})

const selectedList = computed(() => store.lists.find(list => list.id === selectedListId.value))
const items = computed(() => store.itemsByList[selectedListId.value] || [])
const parentOptions = computed(() => store.lists.flatMap(list =>
  (store.itemsByList[list.id] || []).map(item => ({ ...item, listId: list.id, listName: list.name }))
).filter(item => item.id !== editingItemId.value))

onMounted(async () => {
  const lists = await store.fetchLists(props.projectId)
  await Promise.all(lists.map(list => store.fetchItems(props.projectId, list.id)))
  if (lists.length) selectedListId.value = lists[0].id
})

async function createList() {
  if (!listDraft.name.trim()) return
  const created = await store.createList(props.projectId, {
    name: listDraft.name.trim(),
    displayName: listDraft.displayName.trim() || undefined,
    description: listDraft.description.trim() || undefined
  })
  selectedListId.value = created.id
  listDraft.name = ''
  listDraft.displayName = ''
  listDraft.description = ''
}

async function removeList() {
  if (!selectedList.value || !confirm(`Delete reference list "${selectedList.value.name}" and all its items?`)) return
  const id = selectedList.value.id
  await store.deleteList(props.projectId, id)
  selectedListId.value = store.lists[0]?.id || ''
}

function newItem() {
  editingItemId.value = null
  Object.assign(itemDraft, { name: '', label: '', sortOrder: items.value.length, active: true, propertiesText: '', parentItemId: '' })
  showItemEditor.value = true
}

function editItem(item: ReferenceItem) {
  editingItemId.value = item.id
  Object.assign(itemDraft, {
    name: item.name,
    label: item.label,
    sortOrder: item.sortOrder,
    active: item.active,
    propertiesText: Object.entries(item.properties).map(([key, value]) => `${key}=${value}`).join('\n'),
    parentItemId: item.parents[0]?.parentItemId || ''
  })
  showItemEditor.value = true
}

function parseProperties(text: string) {
  const result: Record<string, string> = {}
  for (const line of text.split(/\r?\n/)) {
    const trimmed = line.trim()
    if (!trimmed) continue
    const separator = trimmed.indexOf('=')
    if (separator < 1) continue
    result[trimmed.slice(0, separator).trim()] = trimmed.slice(separator + 1).trim()
  }
  return result
}

async function saveItem() {
  if (!selectedListId.value || !itemDraft.name.trim() || !itemDraft.label.trim()) return
  const payload = {
    name: itemDraft.name.trim(),
    label: itemDraft.label.trim(),
    sortOrder: Number(itemDraft.sortOrder) || 0,
    active: itemDraft.active,
    properties: parseProperties(itemDraft.propertiesText),
    parents: itemDraft.parentItemId
      ? [{ parentItemId: itemDraft.parentItemId, relationshipType: 'parent' }]
      : []
  }
  if (editingItemId.value) {
    await store.updateItem(props.projectId, selectedListId.value, editingItemId.value, payload)
  } else {
    await store.createItem(props.projectId, selectedListId.value, payload)
  }
  showItemEditor.value = false
}

async function deleteItem(item: ReferenceItem) {
  if (!confirm(`Delete "${item.label}"?`)) return
  await store.deleteItem(props.projectId, selectedListId.value, item.id)
}

async function handleImport(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || !selectedListId.value) return
  const result = await store.importCsv(props.projectId, selectedListId.value, file)
  importResult.value = `Imported: ${result.created} created, ${result.updated} updated, ${result.relationshipsCreated} relationships.` +
    (result.warnings.length ? ` ${result.warnings.length} warning(s).` : '')
  input.value = ''
}

async function downloadCsv() {
  if (!selectedListId.value) return
  const { blob, filename } = await store.exportCsv(props.projectId, selectedListId.value)
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  anchor.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <section class="reference-panel">
    <header class="panel-header">
      <div>
        <h2>Reference Data</h2>
        <p>Manage reusable lists, arbitrary item properties, and parent-child hierarchies.</p>
      </div>
      <span class="summary">{{ store.lists.length }} lists · {{ store.totalItems }} items</span>
    </header>

    <div v-if="store.error" class="alert error">{{ store.error }}</div>
    <div v-if="importResult" class="alert success">{{ importResult }}</div>

    <div class="create-list card">
      <h3>Create reference list</h3>
      <div class="form-grid">
        <label>Name <input v-model="listDraft.name" placeholder="districts" /></label>
        <label>Display name <input v-model="listDraft.displayName" placeholder="Districts" /></label>
        <label class="wide">Description <input v-model="listDraft.description" placeholder="Optional description" /></label>
      </div>
      <button class="btn primary" :disabled="!listDraft.name.trim()" @click="createList">Create List</button>
    </div>

    <div v-if="store.lists.length" class="workspace">
      <aside class="lists card">
        <h3>Lists</h3>
        <button
          v-for="list in store.lists"
          :key="list.id"
          class="list-row"
          :class="{ selected: list.id === selectedListId }"
          @click="selectedListId = list.id"
        >
          <span><strong>{{ list.displayName || list.name }}</strong><small>{{ list.name }}</small></span>
          <b>{{ list.itemCount }}</b>
        </button>
      </aside>

      <main v-if="selectedList" class="items card">
        <div class="items-header">
          <div>
            <h3>{{ selectedList.displayName || selectedList.name }}</h3>
            <p>{{ selectedList.description || 'No description' }}</p>
          </div>
          <div class="toolbar">
            <label class="btn secondary file-button">Import CSV<input type="file" accept=".csv,text/csv" @change="handleImport" /></label>
            <button class="btn secondary" @click="downloadCsv">Export CSV</button>
            <button class="btn primary" @click="newItem">Add Item</button>
            <button class="btn danger" @click="removeList">Delete List</button>
          </div>
        </div>

        <div v-if="showItemEditor" class="editor">
          <h4>{{ editingItemId ? 'Edit item' : 'New item' }}</h4>
          <div class="form-grid">
            <label>Name <input v-model="itemDraft.name" placeholder="kampala" /></label>
            <label>Label <input v-model="itemDraft.label" placeholder="Kampala" /></label>
            <label>Sort order <input v-model.number="itemDraft.sortOrder" type="number" /></label>
            <label>Parent
              <select v-model="itemDraft.parentItemId">
                <option value="">No parent</option>
                <option v-for="option in parentOptions" :key="option.id" :value="option.id">
                  {{ option.listName }} / {{ option.label }}
                </option>
              </select>
            </label>
            <label class="wide">Properties <textarea v-model="itemDraft.propertiesText" rows="4" placeholder="code=KLA&#10;region=central" /></label>
            <label class="checkbox"><input v-model="itemDraft.active" type="checkbox" /> Active</label>
          </div>
          <div class="toolbar">
            <button class="btn primary" @click="saveItem">Save Item</button>
            <button class="btn secondary" @click="showItemEditor = false">Cancel</button>
          </div>
        </div>

        <div v-if="!items.length" class="empty">No items yet. Add an item or import a CSV file.</div>
        <div v-else class="table-wrap">
          <table>
            <thead><tr><th>Label</th><th>Name</th><th>Parent</th><th>Properties</th><th>Status</th><th></th></tr></thead>
            <tbody>
              <tr v-for="item in items" :key="item.id">
                <td><strong>{{ item.label }}</strong></td>
                <td><code>{{ item.name }}</code></td>
                <td>{{ item.parents.map(p => `${p.parentListName}/${p.parentLabel}`).join(', ') || '—' }}</td>
                <td class="properties-cell">
                  <span v-for="(value, key) in item.properties" :key="key"><b>{{ key }}</b>= {{ value }}</span>
                </td>
                <td><span class="badge" :class="{ inactive: !item.active }">{{ item.active ? 'Active' : 'Inactive' }}</span></td>
                <td class="actions"><button @click="editItem(item)">Edit</button><button class="danger-link" @click="deleteItem(item)">Delete</button></td>
              </tr>
            </tbody>
          </table>
        </div>
      </main>
    </div>

    <div v-else-if="!store.loading" class="empty card">No reference lists yet. Create the first list above.</div>
    <div v-else class="empty card">Loading reference data…</div>
  </section>
</template>

<style scoped>
.reference-panel { display: grid; gap: 18px; }
.panel-header, .items-header { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; }
h2, h3, h4 { margin: 0; color: #111827; } p { color: #6b7280; margin: 5px 0 0; }
.summary { white-space: nowrap; color: #4b5563; font-size: 14px; }
.card { border: 1px solid #e5e7eb; background: white; border-radius: 10px; padding: 18px; }
.workspace { display: grid; grid-template-columns: minmax(210px, 260px) minmax(0, 1fr); gap: 16px; align-items: start; }
.lists { display: grid; gap: 7px; }
.list-row { border: 1px solid transparent; border-radius: 7px; padding: 10px; background: #f9fafb; text-align: left; display: flex; justify-content: space-between; gap: 8px; cursor: pointer; }
.list-row.selected { border-color: #93c5fd; background: #eff6ff; }
.list-row span { display: grid; } .list-row small { color: #6b7280; margin-top: 2px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin: 14px 0; }
.form-grid label { display: grid; gap: 5px; font-size: 13px; color: #374151; font-weight: 600; }
.form-grid .wide { grid-column: 1 / -1; } .checkbox { display: flex !important; align-items: center; grid-template-columns: auto 1fr; }
input, select, textarea { width: 100%; box-sizing: border-box; border: 1px solid #d1d5db; border-radius: 6px; padding: 9px 10px; font: inherit; background: white; }
textarea { resize: vertical; }
.toolbar { display: flex; flex-wrap: wrap; gap: 8px; }
.btn { border: 1px solid transparent; border-radius: 6px; padding: 8px 12px; font-weight: 600; cursor: pointer; font-size: 13px; }
.btn.primary { background: #2563eb; color: white; } .btn.secondary { background: white; border-color: #d1d5db; color: #374151; } .btn.danger { background: #fff1f2; color: #be123c; border-color: #fecdd3; }
.btn:disabled { opacity: .5; cursor: not-allowed; }.file-button input { display: none; }
.editor { margin: 18px 0; padding: 16px; border: 1px solid #bfdbfe; background: #eff6ff; border-radius: 8px; }
.table-wrap { overflow-x: auto; margin-top: 16px; } table { width: 100%; border-collapse: collapse; min-width: 760px; }
th, td { border-bottom: 1px solid #e5e7eb; padding: 10px; text-align: left; vertical-align: top; font-size: 13px; } th { color: #6b7280; background: #f9fafb; }
.properties-cell { display: grid; gap: 2px; color: #4b5563; } .badge { display: inline-block; padding: 3px 7px; border-radius: 999px; background: #dcfce7; color: #166534; }.badge.inactive { background: #f3f4f6; color: #6b7280; }
.actions { white-space: nowrap; }.actions button { border: 0; background: none; color: #2563eb; cursor: pointer; margin-right: 8px; }.actions .danger-link { color: #be123c; }
.alert { border-radius: 7px; padding: 10px 12px; font-size: 14px; }.alert.error { color: #991b1b; background: #fef2f2; }.alert.success { color: #166534; background: #f0fdf4; }
.empty { text-align: center; color: #6b7280; padding: 30px; }
@media (max-width: 800px) { .workspace { grid-template-columns: 1fr; } .form-grid { grid-template-columns: 1fr; } .form-grid .wide { grid-column: auto; } .panel-header, .items-header { flex-direction: column; } }
</style>
