<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  useWebFormAssignmentsStore,
  type WebFormAssignment,
} from '@/stores/webFormAssignments'

const props = defineProps<{
  projectId: string
  formId: string
}>()

const auth = useAuthStore()
const store = useWebFormAssignmentsStore()

const userQuery = ref('')
const selectedUserId = ref('')
const selectedRoleId = ref('')
const notice = ref('')

const canManage = computed(() =>
  auth.hasPermission('FORM_UPDATE') ||
  auth.hasPermission('PROJECT_UPDATE') ||
  auth.hasPermission('USER_UPDATE') ||
  ['SYSTEM_ADMIN', 'ADMIN', 'PROJECT_ADMIN', 'PROJECT_MANAGER'].some((role) => auth.hasRole(role)),
)

const userAssignments = computed(() =>
  store.assignments.filter((assignment) => assignment.targetType === 'USER'),
)

const roleAssignments = computed(() =>
  store.assignments.filter((assignment) => assignment.targetType === 'ROLE'),
)

async function load(): Promise<void> {
  try {
    if (canManage.value) {
      await store.loadManagementData(props.projectId, props.formId)
    } else {
      await store.loadAssignments(props.projectId, props.formId)
    }
  } catch {
    // Store exposes user-facing error.
  }
}

async function searchUsers(): Promise<void> {
  if (!canManage.value) return
  try {
    await store.searchUsers(props.projectId, props.formId, userQuery.value)
  } catch {
    // Store exposes user-facing error.
  }
}

async function addUser(): Promise<void> {
  if (!canManage.value || !selectedUserId.value) return
  notice.value = ''
  try {
    const assignment = await store.assignUser(
      props.projectId,
      props.formId,
      selectedUserId.value,
    )
    notice.value = `${assignment.targetName} can now access this web form.`
    selectedUserId.value = ''
  } catch {
    // Store exposes user-facing error.
  }
}

async function addRole(): Promise<void> {
  if (!canManage.value || !selectedRoleId.value) return
  notice.value = ''
  try {
    const assignment = await store.assignRole(
      props.projectId,
      props.formId,
      selectedRoleId.value,
    )
    notice.value = `${assignment.targetName} role can now access this web form.`
    selectedRoleId.value = ''
  } catch {
    // Store exposes user-facing error.
  }
}

async function remove(assignment: WebFormAssignment): Promise<void> {
  if (!canManage.value) return
  if (!window.confirm(`Remove web access for ${assignment.targetName}?`)) return
  notice.value = ''
  try {
    await store.removeAssignment(props.projectId, props.formId, assignment.id)
    notice.value = `Access removed for ${assignment.targetName}.`
  } catch {
    // Store exposes user-facing error.
  }
}

onMounted(load)
</script>

<template>
  <section class="assignment-panel">
    <header class="panel-header">
      <div>
        <h2>Web form access</h2>
        <p>
          Control which browser users and project roles can access this form.
          ODK/Kobo mobile assignments remain separate.
        </p>
      </div>
      <span class="count">{{ store.assignments.length }} assignment{{ store.assignments.length === 1 ? '' : 's' }}</span>
    </header>

    <p v-if="notice" class="notice success">{{ notice }}</p>
    <p v-if="store.error" class="notice error">{{ store.error }}</p>

    <div v-if="canManage" class="management-grid">
      <form class="access-card" @submit.prevent="addUser">
        <div>
          <h3>Specific web user</h3>
          <p>Grant this form to an active member of the project.</p>
        </div>

        <label class="field">
          <span>Search project members</span>
          <div class="search-row">
            <input
              v-model="userQuery"
              type="search"
              placeholder="Name, username or email"
              aria-label="Search project members"
            />
            <button type="button" class="btn secondary" :disabled="store.loading" @click="searchUsers">
              Search
            </button>
          </div>
        </label>

        <label class="field">
          <span>User</span>
          <select v-model="selectedUserId" aria-label="Web form user">
            <option value="">Select project member</option>
            <option v-for="user in store.users" :key="user.id" :value="user.id">
              {{ user.fullName }} — {{ user.username }}
            </option>
          </select>
        </label>

        <button
          class="btn primary"
          type="submit"
          :disabled="!selectedUserId || store.saving"
        >
          {{ store.saving ? 'Saving…' : 'Grant user access' }}
        </button>
      </form>

      <form class="access-card" @submit.prevent="addRole">
        <div>
          <h3>Project role</h3>
          <p>Grant this form to every active project member holding the selected role.</p>
        </div>

        <label class="field">
          <span>Role</span>
          <select v-model="selectedRoleId" aria-label="Web form role">
            <option value="">Select project role</option>
            <option v-for="role in store.roles" :key="role.id" :value="role.id">
              {{ role.displayName || role.name.replace(/_/g, ' ') }}
            </option>
          </select>
        </label>

        <button
          class="btn primary"
          type="submit"
          :disabled="!selectedRoleId || store.saving"
        >
          {{ store.saving ? 'Saving…' : 'Grant role access' }}
        </button>
      </form>
    </div>

    <div v-if="store.loading && !store.assignments.length" class="empty">
      Loading web access…
    </div>

    <div v-else class="assignment-sections">
      <section class="assignment-section">
        <div class="section-heading">
          <div>
            <h3>Assigned users</h3>
            <p>Direct browser access for individual project members.</p>
          </div>
          <strong>{{ userAssignments.length }}</strong>
        </div>

        <div v-if="userAssignments.length" class="assignment-list">
          <article
            v-for="assignment in userAssignments"
            :key="assignment.id"
            class="assignment-row"
            :data-assignment-id="assignment.id"
          >
            <div class="target-icon" aria-hidden="true">U</div>
            <div class="target-copy">
              <strong>{{ assignment.targetName }}</strong>
              <span>{{ assignment.targetDetail || 'Web user' }}</span>
            </div>
            <button
              v-if="canManage"
              type="button"
              class="btn danger small"
              :disabled="store.saving"
              @click="remove(assignment)"
            >
              Remove
            </button>
          </article>
        </div>
        <p v-else class="empty compact">No users are directly assigned.</p>
      </section>

      <section class="assignment-section">
        <div class="section-heading">
          <div>
            <h3>Assigned roles</h3>
            <p>Role-based browser access inherited by matching project members.</p>
          </div>
          <strong>{{ roleAssignments.length }}</strong>
        </div>

        <div v-if="roleAssignments.length" class="assignment-list">
          <article
            v-for="assignment in roleAssignments"
            :key="assignment.id"
            class="assignment-row"
            :data-assignment-id="assignment.id"
          >
            <div class="target-icon" aria-hidden="true">R</div>
            <div class="target-copy">
              <strong>{{ assignment.targetName }}</strong>
              <span>{{ assignment.targetDetail || 'Project role' }}</span>
            </div>
            <button
              v-if="canManage"
              type="button"
              class="btn danger small"
              :disabled="store.saving"
              @click="remove(assignment)"
            >
              Remove
            </button>
          </article>
        </div>
        <p v-else class="empty compact">No project roles are assigned.</p>
      </section>
    </div>
  </section>
</template>

<style scoped>
.assignment-panel { display: grid; gap: 18px; }
.panel-header, .section-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 18px; }
h2, h3, p { margin-top: 0; }
.panel-header h2 { margin-bottom: 5px; }
.panel-header p, .access-card p, .section-heading p { color: #6b7280; margin-bottom: 0; line-height: 1.45; }
.count { white-space: nowrap; color: #4b5563; font-size: 13px; font-weight: 700; }
.management-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.access-card, .assignment-section { display: grid; gap: 14px; padding: 18px; border: 1px solid #e5e7eb; border-radius: 10px; background: #fff; }
.access-card { background: #f9fafb; align-content: start; }
.field { display: grid; gap: 6px; color: #374151; font-size: 13px; font-weight: 650; }
input, select { width: 100%; box-sizing: border-box; border: 1px solid #d1d5db; border-radius: 6px; padding: 9px 10px; background: #fff; font: inherit; }
.search-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
.btn { border: 1px solid transparent; border-radius: 6px; padding: 9px 12px; font: inherit; font-size: 13px; font-weight: 700; cursor: pointer; }
.btn.primary { background: #2563eb; color: #fff; }
.btn.secondary { background: #fff; color: #374151; border-color: #d1d5db; }
.btn.danger { background: #fff1f2; color: #be123c; border-color: #fecdd3; }
.btn.small { padding: 7px 10px; }
.btn:disabled { opacity: .55; cursor: not-allowed; }
.assignment-sections { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.section-heading h3 { margin-bottom: 4px; }
.section-heading > strong { display: grid; place-items: center; min-width: 28px; height: 28px; border-radius: 999px; background: #f3f4f6; color: #374151; }
.assignment-list { display: grid; gap: 8px; }
.assignment-row { display: grid; grid-template-columns: 34px minmax(0, 1fr) auto; gap: 10px; align-items: center; padding: 11px; border: 1px solid #e5e7eb; border-radius: 8px; }
.target-icon { display: grid; place-items: center; width: 32px; height: 32px; border-radius: 8px; background: #eff6ff; color: #1d4ed8; font-size: 12px; font-weight: 800; }
.target-copy { min-width: 0; display: grid; gap: 2px; }
.target-copy strong, .target-copy span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.target-copy span { color: #6b7280; font-size: 12px; }
.notice { margin: 0; padding: 10px 12px; border-radius: 7px; font-size: 13px; }
.notice.success { background: #ecfdf5; color: #047857; }
.notice.error { background: #fef2f2; color: #b91c1c; }
.empty { text-align: center; padding: 22px; color: #6b7280; }
.empty.compact { padding: 10px 0; margin: 0; }
@media (max-width: 820px) {
  .management-grid, .assignment-sections { grid-template-columns: 1fr; }
}
@media (max-width: 520px) {
  .panel-header { flex-direction: column; }
  .search-row { grid-template-columns: 1fr; }
  .assignment-row { grid-template-columns: 34px minmax(0, 1fr); }
  .assignment-row .btn { grid-column: 1 / -1; }
}
</style>
