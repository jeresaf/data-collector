<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import api from '@/services/api'

interface AppUserCandidate {
  id: string
  username: string
  email: string
  fullName: string | null
  verified: boolean
  assigned: boolean
  saving?: boolean
}

const props = defineProps<{
  projectId: string
  formId: string
}>()

const loading = ref(false)
const error = ref('')
const notice = ref('')
const query = ref('')
const candidates = ref<AppUserCandidate[]>([])

const filteredCandidates = computed(() => {
  const needle = query.value.trim().toLowerCase()
  if (!needle) return candidates.value
  return candidates.value.filter((user) =>
    [user.username, user.email, user.fullName || '']
      .some((value) => value.toLowerCase().includes(needle)),
  )
})

const assignedCount = computed(() => candidates.value.filter((user) => user.assigned).length)

function errorMessage(err: any, fallback: string): string {
  return err?.response?.data?.message
    || err?.response?.data?.error
    || err?.message
    || fallback
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  notice.value = ''
  try {
    const response = await api.get<AppUserCandidate[]>(
      `/projects/${props.projectId}/mobile-form-assignments/${props.formId}`,
    )
    candidates.value = (response.data || []).map((user) => ({ ...user, saving: false }))
  } catch (err: any) {
    error.value = errorMessage(err, 'Unable to load mobile App User assignments.')
  } finally {
    loading.value = false
  }
}

async function assign(user: AppUserCandidate): Promise<void> {
  user.saving = true
  error.value = ''
  notice.value = ''
  try {
    const response = await api.post<AppUserCandidate>(
      `/projects/${props.projectId}/mobile-form-assignments/${props.formId}/app-users/${user.id}`,
    )
    Object.assign(user, response.data, { saving: false })
    notice.value = `${user.fullName || user.username} can now download and submit this form from the mobile app.`
  } catch (err: any) {
    error.value = errorMessage(err, 'Unable to assign the form to this App User.')
  } finally {
    user.saving = false
  }
}

async function remove(user: AppUserCandidate): Promise<void> {
  if (!window.confirm(`Remove ${user.fullName || user.username} from this mobile form?`)) return

  user.saving = true
  error.value = ''
  notice.value = ''
  try {
    const response = await api.delete<AppUserCandidate>(
      `/projects/${props.projectId}/mobile-form-assignments/${props.formId}/app-users/${user.id}`,
    )
    Object.assign(user, response.data, { saving: false })
    notice.value = `${user.fullName || user.username} no longer has mobile access to this form.`
  } catch (err: any) {
    error.value = errorMessage(err, 'Unable to remove the mobile form assignment.')
  } finally {
    user.saving = false
  }
}

onMounted(load)
</script>

<template>
  <section class="mobile-assignments">
    <header class="panel-header">
      <div>
        <h2>Mobile App User assignments</h2>
        <p>
          Assign this form to ODK/Kobo App Users. These assignments are separate
          from browser Web access and control mobile form download/submission access.
        </p>
      </div>
      <span class="count">{{ assignedCount }} assigned</span>
    </header>

    <div class="info-box">
      Create and manage App User accounts in <strong>Identity Administration → App users</strong>.
      This tab controls only the forms assigned to those mobile accounts.
    </div>

    <p v-if="notice" class="notice success">{{ notice }}</p>
    <p v-if="error" class="notice error" role="alert">{{ error }}</p>

    <div class="toolbar">
      <label>
        <span>Search App Users</span>
        <input v-model="query" type="search" placeholder="Name, username or email" />
      </label>
      <button type="button" class="btn secondary" :disabled="loading" @click="load">
        {{ loading ? 'Refreshing…' : 'Refresh' }}
      </button>
    </div>

    <div v-if="loading && !candidates.length" class="empty">Loading mobile App Users…</div>

    <div v-else-if="filteredCandidates.length" class="user-list">
      <article v-for="user in filteredCandidates" :key="user.id" class="user-row">
        <div class="user-copy">
          <div class="name-line">
            <strong>{{ user.fullName || user.username }}</strong>
            <span v-if="user.assigned" class="status assigned">Assigned</span>
            <span v-else class="status available">Not assigned</span>
          </div>
          <span>{{ user.username }} · {{ user.email }}</span>
          <small>{{ user.verified ? 'Verified App User' : 'App User' }}</small>
        </div>

        <button
          v-if="!user.assigned"
          type="button"
          class="btn primary"
          :disabled="user.saving"
          @click="assign(user)"
        >
          {{ user.saving ? 'Assigning…' : 'Assign form' }}
        </button>
        <button
          v-else
          type="button"
          class="btn danger"
          :disabled="user.saving"
          @click="remove(user)"
        >
          {{ user.saving ? 'Removing…' : 'Remove' }}
        </button>
      </article>
    </div>

    <div v-else-if="!error" class="empty">
      {{ query ? 'No App Users match your search.' : 'No active App Users are available.' }}
    </div>
  </section>
</template>

<style scoped>
.mobile-assignments { display: grid; gap: 16px; }
.panel-header { display: flex; justify-content: space-between; gap: 18px; align-items: flex-start; }
h2, p { margin-top: 0; }
.panel-header p { margin-bottom: 0; color: #6b7280; max-width: 760px; }
.count { border-radius: 999px; background: #eff6ff; color: #1d4ed8; padding: 5px 10px; font-size: 12px; font-weight: 700; white-space: nowrap; }
.info-box { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px 14px; color: #475569; font-size: 14px; }
.notice { padding: 10px 12px; border-radius: 8px; margin: 0; }
.notice.success { background: #ecfdf5; color: #065f46; }
.notice.error { background: #fff1f2; color: #9f1239; }
.toolbar { display: flex; gap: 12px; align-items: end; }
.toolbar label { display: grid; gap: 5px; flex: 1; color: #374151; font-size: 13px; font-weight: 650; }
.toolbar input { width: 100%; box-sizing: border-box; border: 1px solid #d1d5db; border-radius: 7px; padding: 9px 10px; font: inherit; }
.user-list { display: grid; gap: 9px; }
.user-row { display: flex; align-items: center; justify-content: space-between; gap: 18px; border: 1px solid #e5e7eb; border-radius: 9px; padding: 13px 14px; }
.user-copy { display: grid; gap: 3px; min-width: 0; }
.user-copy > span, .user-copy small { color: #6b7280; overflow-wrap: anywhere; }
.name-line { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.status { border-radius: 999px; padding: 3px 7px; font-size: 11px; font-weight: 700; }
.status.assigned { background: #ecfdf5; color: #047857; }
.status.available { background: #f3f4f6; color: #4b5563; }
.btn { border: 1px solid transparent; border-radius: 7px; padding: 8px 11px; font: inherit; font-weight: 650; cursor: pointer; white-space: nowrap; }
.btn:disabled { opacity: .55; cursor: not-allowed; }
.btn.primary { background: #2563eb; color: white; }
.btn.secondary { background: white; border-color: #d1d5db; color: #374151; }
.btn.danger { background: #fff1f2; border-color: #fecdd3; color: #be123c; }
.empty { padding: 24px; border: 1px dashed #cbd5e1; border-radius: 9px; color: #64748b; text-align: center; }
@media (max-width: 720px) {
  .panel-header, .toolbar, .user-row { align-items: stretch; flex-direction: column; }
  .btn { width: 100%; }
}
</style>
