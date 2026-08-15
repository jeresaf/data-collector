<script setup lang="ts">
import { useRouter } from 'vue-router'

withDefaults(defineProps<{
  title?: string
  subtitle?: string
  showNavigation?: boolean
}>(), {
  title: 'Forms',
  subtitle: '',
  showNavigation: true,
})

const router = useRouter()
</script>

<template>
  <div class="app-form-shell">
    <header class="shell-header">
      <div>
        <p class="eyebrow">Data collection</p>
        <h1>{{ title }}</h1>
        <p v-if="subtitle" class="subtitle">{{ subtitle }}</p>
      </div>
    </header>

    <nav v-if="showNavigation" class="shell-nav" aria-label="Form workspace">
      <button type="button" @click="router.push({ name: 'app-forms' })">Forms</button>
      <button type="button" @click="router.push({ name: 'app-drafts' })">Drafts</button>
      <button type="button" @click="router.push({ name: 'app-submitted' })">Submitted</button>
    </nav>

    <main class="shell-content">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.app-form-shell {
  min-height: 100vh;
  background: #f3f4f6;
  color: #111827;
}

.shell-header,
.shell-nav,
.shell-content {
  width: min(760px, calc(100% - 2rem));
  margin: 0 auto;
}

.shell-header {
  padding: 1.5rem 0 1rem;
}

.eyebrow {
  margin: 0 0 .25rem;
  color: #2563eb;
  font-size: .75rem;
  font-weight: 700;
  letter-spacing: .08em;
  text-transform: uppercase;
}

h1 {
  margin: 0;
  font-size: clamp(1.5rem, 5vw, 2rem);
}

.subtitle {
  margin: .35rem 0 0;
  color: #6b7280;
}

.shell-nav {
  display: flex;
  gap: .5rem;
  overflow-x: auto;
  padding-bottom: 1rem;
}

.shell-nav button {
  border: 1px solid #d1d5db;
  border-radius: 999px;
  background: white;
  padding: .625rem 1rem;
  font: inherit;
  cursor: pointer;
  white-space: nowrap;
}

.shell-content {
  padding-bottom: 2rem;
}

@media (min-width: 700px) {
  .shell-header {
    padding-top: 2rem;
  }
}
</style>
