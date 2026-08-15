<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()

const showApplicationHeader = computed(
  () => route.name !== 'login' && authStore.isAuthenticated,
)

onMounted(() => {
  if (authStore.isAuthenticated) {
    void authStore.restoreSession()
  }
})

async function logout() {
  await authStore.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div id="app">
    <header v-if="showApplicationHeader" class="app-header">
      <div class="header-content">
        <h1 class="app-title">ODK Platform</h1>

        <nav class="app-nav" aria-label="Primary navigation">
          <RouterLink to="/" class="nav-link">Projects</RouterLink>
          <RouterLink to="/submissions" class="nav-link">Submissions</RouterLink>
          <RouterLink to="/app/forms" class="nav-link">Forms</RouterLink>
          <RouterLink
            v-if="authStore.hasRole('SYSTEM_ADMIN')"
            to="/admin/identity"
            class="nav-link"
          >
            Users
          </RouterLink>
        </nav>

        <div class="auth-actions">
          <span v-if="authStore.displayName" class="user-name">
            {{ authStore.displayName }}
          </span>
          <button class="logout-button" type="button" @click="logout">
            Sign out
          </button>
        </div>
      </div>
    </header>

    <main class="app-main" :class="{ 'app-main-login': route.name === 'login' }">
      <RouterView />
    </main>
  </div>
</template>

<style>
/* Global application styles remain in ./styles/main.css. */

.auth-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}

.user-name {
  color: #4b5563;
  font-size: 14px;
  font-weight: 600;
}

.logout-button {
  padding: 7px 12px;
  border: 1px solid #d1d5db;
  border-radius: 7px;
  background: #fff;
  color: #374151;
  cursor: pointer;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
}

.logout-button:hover {
  background: #f9fafb;
}

.app-main-login {
  padding: 0;
}

@media (max-width: 720px) {
  .header-content {
    flex-wrap: wrap;
  }

  .app-nav {
    order: 3;
    width: 100%;
    overflow-x: auto;
  }

  .auth-actions {
    margin-left: auto;
  }

  .user-name {
    max-width: 35vw;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
</style>
