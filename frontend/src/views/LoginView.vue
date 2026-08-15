<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')

const errorMessage = computed(() => authStore.error)

function safeRedirect(value: unknown): string {
  if (typeof value !== 'string') {
    return '/'
  }

  if (!value.startsWith('/') || value.startsWith('//')) {
    return '/'
  }

  return value
}

async function submitLogin() {
  if (!username.value.trim() || !password.value) {
    return
  }

  try {
    await authStore.login(username.value.trim(), password.value)
    await router.replace(safeRedirect(route.query.redirect))
  } catch {
    // The auth store exposes the server-friendly error message.
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card" aria-labelledby="login-title">
      <div class="brand-block">
        <span class="brand-mark">ODK</span>
        <div>
          <h1 id="login-title">Sign in to ODK Platform</h1>
          <p>Use your web account to manage projects, forms and submissions.</p>
        </div>
      </div>

      <form class="login-form" @submit.prevent="submitLogin">
        <label for="username">Username</label>
        <input
          id="username"
          v-model="username"
          name="username"
          type="text"
          autocomplete="username"
          autocapitalize="none"
          spellcheck="false"
          required
          :disabled="authStore.loading"
        />

        <label for="password">Password</label>
        <input
          id="password"
          v-model="password"
          name="password"
          type="password"
          autocomplete="current-password"
          required
          :disabled="authStore.loading"
        />

        <p v-if="errorMessage" class="login-error" role="alert">
          {{ errorMessage }}
        </p>

        <button
          class="login-button"
          type="submit"
          :disabled="authStore.loading || !username.trim() || !password"
        >
          {{ authStore.loading ? 'Signing in…' : 'Sign in' }}
        </button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(circle at top left, rgba(37, 99, 235, 0.12), transparent 34rem),
    #f6f8fb;
}

.login-card {
  width: min(100%, 440px);
  padding: 32px;
  border: 1px solid #e5e7eb;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 18px 45px rgba(15, 23, 42, 0.08);
}

.brand-block {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 28px;
}

.brand-mark {
  display: grid;
  width: 50px;
  height: 50px;
  flex: 0 0 50px;
  place-items: center;
  border-radius: 12px;
  background: #2563eb;
  color: #fff;
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0.05em;
}

h1 {
  margin: 0;
  color: #111827;
  font-size: 24px;
  line-height: 1.2;
}

p {
  margin: 8px 0 0;
  color: #6b7280;
  line-height: 1.5;
}

.login-form {
  display: grid;
  gap: 10px;
}

.login-form label {
  margin-top: 8px;
  color: #374151;
  font-size: 14px;
  font-weight: 600;
}

.login-form input {
  width: 100%;
  box-sizing: border-box;
  padding: 11px 12px;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  background: #fff;
  color: #111827;
  font: inherit;
}

.login-form input:focus {
  outline: none;
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.login-form input:disabled {
  background: #f3f4f6;
}

.login-error {
  margin: 8px 0 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fef2f2;
  color: #991b1b;
  font-size: 14px;
}

.login-button {
  margin-top: 12px;
  padding: 11px 16px;
  border: 0;
  border-radius: 8px;
  background: #2563eb;
  color: #fff;
  cursor: pointer;
  font: inherit;
  font-weight: 700;
}

.login-button:hover:not(:disabled) {
  background: #1d4ed8;
}

.login-button:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}

@media (max-width: 560px) {
  .login-page {
    padding: 16px;
  }

  .login-card {
    padding: 24px 20px;
  }
}
</style>
