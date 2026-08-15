<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  useIdentityAdminStore,
  type AppUserAdmin,
  type WebUserAdmin,
} from '@/stores/identityAdmin'

const store = useIdentityAdminStore()
const activeTab = ref<'users' | 'app-users' | 'roles'>('users')
const notice = ref('')
const editor = ref<'user' | 'app-user' | null>(null)
const editingUserId = ref<string | null>(null)
const editingAppUserId = ref<string | null>(null)
const selectedRoles = ref<string[]>([])

const userDraft = reactive({
  username: '',
  email: '',
  password: '',
  firstName: '',
  lastName: '',
  phoneNumber: '',
})

const appUserDraft = reactive({
  username: '',
  email: '',
  password: '',
  fullName: '',
  phoneNumber: '',
})

const sortedRoles = computed(() =>
  [...store.roles].sort((a, b) =>
    (a.displayName || a.name).localeCompare(b.displayName || b.name),
  ),
)

function resetUserEditor() {
  editingUserId.value = null
  Object.assign(userDraft, {
    username: '',
    email: '',
    password: '',
    firstName: '',
    lastName: '',
    phoneNumber: '',
  })
  selectedRoles.value = store.roles.some(role => role.name === 'VIEWER')
    ? ['VIEWER']
    : []
}

function resetAppUserEditor() {
  editingAppUserId.value = null
  Object.assign(appUserDraft, {
    username: '',
    email: '',
    password: '',
    fullName: '',
    phoneNumber: '',
  })
}

function newUser() {
  notice.value = ''
  resetUserEditor()
  editor.value = 'user'
}

function editUser(user: WebUserAdmin) {
  notice.value = ''
  editingUserId.value = user.id
  Object.assign(userDraft, {
    username: user.username,
    email: user.email,
    password: '',
    firstName: user.firstName || '',
    lastName: user.lastName || '',
    phoneNumber: user.phoneNumber || '',
  })
  selectedRoles.value = [...user.roles]
  editor.value = 'user'
}

function newAppUser() {
  notice.value = ''
  resetAppUserEditor()
  editor.value = 'app-user'
}

function editAppUser(user: AppUserAdmin) {
  notice.value = ''
  editingAppUserId.value = user.id
  Object.assign(appUserDraft, {
    username: user.username,
    email: user.email,
    password: '',
    fullName: user.fullName || '',
    phoneNumber: user.phoneNumber || '',
  })
  editor.value = 'app-user'
}

async function saveUser() {
  notice.value = ''
  try {
    const payload = {
      ...userDraft,
      password: userDraft.password || undefined,
      roleNames: [...selectedRoles.value],
    }

    if (editingUserId.value) {
      await store.updateUser(editingUserId.value, payload)
      await store.replaceRoles(editingUserId.value, selectedRoles.value)
      notice.value = 'Web user updated.'
    } else {
      if (!userDraft.password) throw new Error('Password is required for a new user.')
      await store.createUser(payload)
      notice.value = 'Web user created.'
    }

    editor.value = null
  } catch (error: any) {
    notice.value = error?.response?.data?.error
      || error?.message
      || 'Unable to save web user.'
  }
}

async function saveAppUser() {
  notice.value = ''
  try {
    const payload = {
      ...appUserDraft,
      password: appUserDraft.password || undefined,
    }

    if (editingAppUserId.value) {
      await store.updateAppUser(editingAppUserId.value, payload)
      notice.value = 'App user updated.'
    } else {
      if (!appUserDraft.password) throw new Error('Password is required for a new app user.')
      await store.createAppUser(payload)
      notice.value = 'App user created.'
    }

    editor.value = null
  } catch (error: any) {
    notice.value = error?.response?.data?.error
      || error?.message
      || 'Unable to save app user.'
  }
}

async function toggleUser(user: WebUserAdmin) {
  if (!window.confirm(`${user.active ? 'Deactivate' : 'Activate'} ${user.username}?`)) return
  try {
    await store.setUserActive(user.id, !user.active)
  } catch (error: any) {
    notice.value = error?.response?.data?.error || 'Unable to change user status.'
  }
}

async function toggleAppUser(user: AppUserAdmin) {
  if (!window.confirm(`${user.active ? 'Deactivate' : 'Activate'} ${user.username}?`)) return
  try {
    await store.setAppUserActive(user.id, !user.active)
  } catch (error: any) {
    notice.value = error?.response?.data?.error || 'Unable to change app-user status.'
  }
}

function formatDate(value: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

onMounted(() => {
  store.fetchAll().catch(() => undefined)
})
</script>

<template>
  <main class="identity-page">
    <header class="page-header">
      <div>
        <p class="eyebrow">System administration</p>
        <h1>Identity Administration</h1>
        <p class="subtitle">
          Manage browser users, ODK/Kobo app users, and global roles.
        </p>
      </div>

      <button
        v-if="activeTab === 'users'"
        class="primary"
        type="button"
        @click="newUser"
      >
        New web user
      </button>
      <button
        v-else-if="activeTab === 'app-users'"
        class="primary"
        type="button"
        @click="newAppUser"
      >
        New app user
      </button>
    </header>

    <nav class="tabs" aria-label="Identity administration">
      <button :class="{ active: activeTab === 'users' }" @click="activeTab = 'users'">
        Web users
      </button>
      <button :class="{ active: activeTab === 'app-users' }" @click="activeTab = 'app-users'">
        App users
      </button>
      <button :class="{ active: activeTab === 'roles' }" @click="activeTab = 'roles'">
        Global roles
      </button>
    </nav>

    <p v-if="store.error" class="notice error" role="alert">{{ store.error }}</p>
    <p v-if="notice" class="notice">{{ notice }}</p>

    <section v-if="editor === 'user'" class="editor-card">
      <div class="editor-header">
        <h2>{{ editingUserId ? 'Edit web user' : 'Create web user' }}</h2>
        <button class="link-button" type="button" @click="editor = null">Close</button>
      </div>

      <form @submit.prevent="saveUser">
        <div class="form-grid">
          <label>
            Username
            <input v-model.trim="userDraft.username" required autocomplete="off" />
          </label>
          <label>
            Email
            <input v-model.trim="userDraft.email" type="email" required autocomplete="off" />
          </label>
          <label>
            {{ editingUserId ? 'New password (optional)' : 'Password' }}
            <input
              v-model="userDraft.password"
              type="password"
              :required="!editingUserId"
              minlength="8"
              autocomplete="new-password"
            />
          </label>
          <label>
            Phone
            <input v-model.trim="userDraft.phoneNumber" autocomplete="off" />
          </label>
          <label>
            First name
            <input v-model.trim="userDraft.firstName" autocomplete="off" />
          </label>
          <label>
            Last name
            <input v-model.trim="userDraft.lastName" autocomplete="off" />
          </label>
        </div>

        <fieldset>
          <legend>Global roles</legend>
          <label v-for="role in sortedRoles" :key="role.id" class="role-check">
            <input v-model="selectedRoles" type="checkbox" :value="role.name" />
            <span>
              <strong>{{ role.displayName || role.name }}</strong>
              <small>{{ role.name }}</small>
            </span>
          </label>
        </fieldset>

        <div class="form-actions">
          <button type="button" class="secondary" @click="editor = null">Cancel</button>
          <button type="submit" class="primary">Save web user</button>
        </div>
      </form>
    </section>

    <section v-if="editor === 'app-user'" class="editor-card">
      <div class="editor-header">
        <h2>{{ editingAppUserId ? 'Edit app user' : 'Create app user' }}</h2>
        <button class="link-button" type="button" @click="editor = null">Close</button>
      </div>

      <form @submit.prevent="saveAppUser">
        <div class="form-grid">
          <label>
            Username
            <input v-model.trim="appUserDraft.username" required autocomplete="off" />
          </label>
          <label>
            Email
            <input v-model.trim="appUserDraft.email" type="email" required autocomplete="off" />
          </label>
          <label>
            {{ editingAppUserId ? 'New password (optional)' : 'Password' }}
            <input
              v-model="appUserDraft.password"
              type="password"
              :required="!editingAppUserId"
              minlength="8"
              autocomplete="new-password"
            />
          </label>
          <label>
            Full name
            <input v-model.trim="appUserDraft.fullName" autocomplete="off" />
          </label>
          <label>
            Phone
            <input v-model.trim="appUserDraft.phoneNumber" autocomplete="off" />
          </label>
        </div>

        <div class="form-actions">
          <button type="button" class="secondary" @click="editor = null">Cancel</button>
          <button type="submit" class="primary">Save app user</button>
        </div>
      </form>
    </section>

    <div v-if="store.loading" class="state-card">Loading identity data…</div>

    <section v-else-if="activeTab === 'users'" class="table-card">
      <table>
        <thead>
          <tr>
            <th>User</th>
            <th>Roles</th>
            <th>Status</th>
            <th>Last login</th>
            <th aria-label="Actions"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in store.users" :key="user.id">
            <td>
              <strong>{{ user.fullName || user.username }}</strong>
              <small>{{ user.username }} · {{ user.email }}</small>
            </td>
            <td>
              <span v-for="role in user.roles" :key="role" class="pill">{{ role }}</span>
            </td>
            <td>
              <span class="status" :class="{ inactive: !user.active }">
                {{ user.active ? 'Active' : 'Inactive' }}
              </span>
            </td>
            <td>{{ formatDate(user.lastLoginAt) }}</td>
            <td class="row-actions">
              <button type="button" @click="editUser(user)">Edit</button>
              <button type="button" @click="toggleUser(user)">
                {{ user.active ? 'Deactivate' : 'Activate' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-else-if="activeTab === 'app-users'" class="table-card">
      <table>
        <thead>
          <tr>
            <th>App user</th>
            <th>Status</th>
            <th>Last login</th>
            <th aria-label="Actions"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in store.appUsers" :key="user.id">
            <td>
              <strong>{{ user.fullName || user.username }}</strong>
              <small>{{ user.username }} · {{ user.email }}</small>
            </td>
            <td>
              <span class="status" :class="{ inactive: !user.active }">
                {{ user.active ? 'Active' : 'Inactive' }}
              </span>
            </td>
            <td>{{ formatDate(user.lastLoginAt) }}</td>
            <td class="row-actions">
              <button type="button" @click="editAppUser(user)">Edit</button>
              <button type="button" @click="toggleAppUser(user)">
                {{ user.active ? 'Deactivate' : 'Activate' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-else class="role-grid">
      <article v-for="role in sortedRoles" :key="role.id" class="role-card">
        <div>
          <h2>{{ role.displayName || role.name }}</h2>
          <code>{{ role.name }}</code>
        </div>
        <p>{{ role.description || 'No description.' }}</p>
        <span v-if="role.system" class="pill">System role</span>
      </article>
    </section>
  </main>
</template>

<style scoped>
.identity-page {
  width: min(1180px, calc(100% - 2rem));
  margin: 0 auto;
  padding: 1.5rem 0 3rem;
}

.page-header,
.editor-header,
.form-actions,
.row-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: .75rem;
}

.eyebrow {
  margin: 0 0 .25rem;
  color: #2563eb;
  font-size: .75rem;
  font-weight: 700;
  letter-spacing: .08em;
  text-transform: uppercase;
}

h1, h2 { margin: 0; }
.subtitle { margin: .4rem 0 0; color: #6b7280; }

.tabs {
  display: flex;
  gap: .5rem;
  margin: 1.25rem 0;
  overflow-x: auto;
}

.tabs button,
.row-actions button,
.link-button {
  border: 0;
  background: transparent;
  color: #374151;
  font: inherit;
  cursor: pointer;
}

.tabs button {
  border-bottom: 2px solid transparent;
  padding: .6rem .8rem;
  white-space: nowrap;
}

.tabs button.active {
  border-bottom-color: #2563eb;
  color: #1d4ed8;
  font-weight: 700;
}

.primary,
.secondary {
  border: 0;
  border-radius: .5rem;
  padding: .7rem 1rem;
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}

.primary { background: #2563eb; color: white; }
.secondary { background: #e5e7eb; color: #374151; }

.notice,
.state-card,
.editor-card,
.table-card,
.role-card {
  border: 1px solid #e5e7eb;
  border-radius: .75rem;
  background: white;
}

.notice,
.state-card {
  margin: 1rem 0;
  padding: .85rem 1rem;
}

.error { border-color: #fecaca; color: #b91c1c; }

.editor-card {
  margin-bottom: 1rem;
  padding: 1rem;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: .8rem;
  margin: 1rem 0;
}

label {
  display: grid;
  gap: .3rem;
  color: #374151;
  font-size: .875rem;
  font-weight: 600;
}

input {
  border: 1px solid #d1d5db;
  border-radius: .45rem;
  padding: .65rem .7rem;
  font: inherit;
}

fieldset {
  display: flex;
  flex-wrap: wrap;
  gap: .65rem;
  margin: 0 0 1rem;
  border: 1px solid #e5e7eb;
  border-radius: .6rem;
}

.role-check {
  display: flex;
  grid-template-columns: none;
  align-items: flex-start;
  gap: .45rem;
  min-width: 13rem;
}

.role-check input { margin-top: .2rem; }
.role-check small, td small {
  display: block;
  margin-top: .2rem;
  color: #6b7280;
  font-weight: 400;
}

.table-card { overflow-x: auto; }

table {
  width: 100%;
  border-collapse: collapse;
}

th, td {
  padding: .8rem;
  border-bottom: 1px solid #f3f4f6;
  text-align: left;
  vertical-align: top;
}

th {
  color: #6b7280;
  font-size: .75rem;
  text-transform: uppercase;
}

.pill,
.status {
  display: inline-flex;
  margin: 0 .3rem .3rem 0;
  border-radius: 999px;
  background: #dbeafe;
  color: #1e40af;
  padding: .25rem .55rem;
  font-size: .75rem;
  font-weight: 700;
}

.status { background: #dcfce7; color: #166534; }
.status.inactive { background: #f3f4f6; color: #6b7280; }

.row-actions {
  justify-content: flex-end;
  white-space: nowrap;
}

.row-actions button {
  color: #2563eb;
  font-weight: 600;
}

.role-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
  gap: .8rem;
}

.role-card { padding: 1rem; }
.role-card p { color: #6b7280; }

@media (max-width: 700px) {
  .page-header { align-items: stretch; flex-direction: column; }
  .form-grid { grid-template-columns: 1fr; }
  .row-actions { flex-direction: column; align-items: flex-start; }
}
</style>
