<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useProjectMembersStore, type ProjectMember } from '@/stores/projectMembers'

const props = defineProps<{
  projectId: string
}>()

const authStore = useAuthStore()
const store = useProjectMembersStore()

const query = ref('')
const selectedUserId = ref('')
const selectedRoleId = ref('')
const notice = ref('')

const canManage = computed(() => {
  const roles = authStore.user?.roles ?? []
  return (
    authStore.hasPermission('PROJECT_UPDATE') ||
    authStore.hasPermission('USER_UPDATE') ||
    roles.some(role =>
      ['SYSTEM_ADMIN', 'ADMIN', 'PROJECT_ADMIN', 'PROJECT_MANAGER'].includes(role),
    )
  )
})

const availableCandidates = computed(() => store.candidates)

function roleLabel(member: ProjectMember) {
  return member.roleDisplayName || member.roleName.replace(/_/g, ' ')
}

async function search() {
  await store.searchCandidates(props.projectId, query.value)
}

async function addMember() {
  if (!selectedUserId.value || !selectedRoleId.value) return

  const added = await store.addMember(
    props.projectId,
    selectedUserId.value,
    selectedRoleId.value,
  )

  selectedUserId.value = ''
  selectedRoleId.value = ''
  notice.value = `${added.fullName} added to the project.`
}

async function removeMember(member: ProjectMember) {
  if (!confirm(`Remove ${member.fullName} as ${roleLabel(member)}?`)) return

  await store.removeMember(props.projectId, member.id)
  notice.value = `${member.fullName} removed from ${roleLabel(member)}.`
}

onMounted(async () => {
  if (canManage.value) {
    await store.loadManagementData(props.projectId)
  } else {
    await store.fetchMembers(props.projectId)
  }
})
</script>

<template>
  <section class="members-panel" aria-labelledby="project-members-title">
    <header class="panel-header">
      <div>
        <h2 id="project-members-title">Users &amp; Access</h2>
        <p>
          Assign web users to this project with project-scoped roles.
          The project owner retains access independently of these assignments.
        </p>
      </div>
    </header>

    <div v-if="store.error" class="alert error" role="alert">
      {{ store.error }}
    </div>

    <div v-if="notice" class="alert success" role="status">
      {{ notice }}
    </div>

    <form
      v-if="canManage"
      class="assignment-form"
      @submit.prevent="addMember"
    >
      <div class="assignment-heading">
        <div>
          <h3>Add project access</h3>
          <p>Select an active web user and the role they should have in this project.</p>
        </div>
      </div>

      <div class="search-row">
        <label>
          <span>Find user</span>
          <input
            v-model="query"
            type="search"
            placeholder="Name, username or email"
            @keydown.enter.prevent="search"
          />
        </label>
        <button
          type="button"
          class="btn btn-secondary"
          :disabled="store.loading"
          @click="search"
        >
          Search
        </button>
      </div>

      <div class="assignment-grid">
        <label>
          <span>User</span>
          <select v-model="selectedUserId" aria-label="Project user">
            <option value="">Select user</option>
            <option
              v-for="candidate in availableCandidates"
              :key="candidate.id"
              :value="candidate.id"
            >
              {{ candidate.fullName }} — {{ candidate.username }}
            </option>
          </select>
        </label>

        <label>
          <span>Project role</span>
          <select v-model="selectedRoleId" aria-label="Project role">
            <option value="">Select role</option>
            <option
              v-for="role in store.roles"
              :key="role.id"
              :value="role.id"
            >
              {{ role.displayName || role.name.replace(/_/g, ' ') }}
            </option>
          </select>
        </label>

        <button
          class="btn btn-primary add-button"
          type="submit"
          :disabled="!selectedUserId || !selectedRoleId || store.saving"
        >
          {{ store.saving ? 'Adding…' : 'Add access' }}
        </button>
      </div>
    </form>

    <div class="members-section">
      <div class="section-heading">
        <div>
          <h3>Current project access</h3>
          <p>{{ store.members.length }} role assignment{{ store.members.length === 1 ? '' : 's' }}</p>
        </div>
      </div>

      <div v-if="store.loading && !store.members.length" class="loading-state">
        Loading project access…
      </div>

      <div v-else-if="!store.members.length" class="empty-state">
        <h4>No explicit project members</h4>
        <p>Add a web user above to give them project-scoped access.</p>
      </div>

      <div v-else class="member-list">
        <article
          v-for="member in store.members"
          :key="member.id"
          class="member-row"
          :data-member-id="member.id"
        >
          <div class="avatar" aria-hidden="true">
            {{ member.fullName.slice(0, 1).toUpperCase() }}
          </div>

          <div class="member-copy">
            <strong>{{ member.fullName }}</strong>
            <span>{{ member.username }} · {{ member.email }}</span>
          </div>

          <span class="role-badge">{{ roleLabel(member) }}</span>

          <button
            v-if="canManage"
            class="btn btn-danger btn-small"
            type="button"
            :disabled="store.saving"
            @click="removeMember(member)"
          >
            Remove
          </button>
        </article>
      </div>
    </div>
  </section>
</template>

<style scoped>
.members-panel {
  display: grid;
  gap: 22px;
}

.panel-header h2,
.assignment-form h3,
.members-section h3,
.empty-state h4 {
  margin: 0;
  color: #111827;
}

.panel-header p,
.assignment-heading p,
.section-heading p,
.empty-state p {
  margin: 6px 0 0;
  color: #6b7280;
  line-height: 1.45;
}

.assignment-form {
  display: grid;
  gap: 16px;
  padding: 20px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #f9fafb;
}

.search-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 12px;
}

.assignment-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr) auto;
  align-items: end;
  gap: 12px;
}

label {
  display: grid;
  gap: 6px;
}

label > span {
  color: #374151;
  font-size: 13px;
  font-weight: 600;
}

input,
select {
  width: 100%;
  min-height: 40px;
  box-sizing: border-box;
  padding: 8px 10px;
  border: 1px solid #d1d5db;
  border-radius: 7px;
  background: white;
  color: #111827;
  font: inherit;
}

.add-button {
  min-height: 40px;
}

.members-section {
  display: grid;
  gap: 12px;
}

.section-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.member-list {
  display: grid;
  gap: 10px;
}

.member-row {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) auto auto;
  gap: 14px;
  align-items: center;
  padding: 14px 16px;
  border: 1px solid #e5e7eb;
  border-radius: 9px;
  background: white;
}

.avatar {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 50%;
  background: #eff6ff;
  color: #1d4ed8;
  font-weight: 700;
}

.member-copy {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.member-copy strong {
  color: #111827;
}

.member-copy span {
  overflow: hidden;
  color: #6b7280;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.role-badge {
  padding: 5px 9px;
  border-radius: 999px;
  background: #f3f4f6;
  color: #374151;
  font-size: 12px;
  font-weight: 600;
}

.btn-small {
  padding: 6px 10px;
}

.alert,
.loading-state,
.empty-state {
  padding: 14px 16px;
  border-radius: 8px;
}

.alert.error {
  background: #fef2f2;
  color: #991b1b;
}

.alert.success {
  background: #f0fdf4;
  color: #166534;
}

.loading-state,
.empty-state {
  border: 1px dashed #d1d5db;
  background: #f9fafb;
  color: #6b7280;
}

@media (max-width: 760px) {
  .search-row,
  .assignment-grid,
  .member-row {
    grid-template-columns: 1fr;
  }

  .avatar {
    display: none;
  }

  .role-badge {
    justify-self: start;
  }
}
</style>
