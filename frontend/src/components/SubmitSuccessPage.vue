<script setup lang="ts">
import { useRouter } from 'vue-router'

defineProps<{
  instanceId?: string | null
}>()

const router = useRouter()
</script>

<template>
  <section class="success-card" role="status">
    <div class="success-icon" aria-hidden="true">✓</div>
    <h2>Submission complete</h2>
    <p>Your form has been submitted successfully.</p>
    <p v-if="instanceId" class="receipt">Instance: {{ instanceId }}</p>
    <button type="button" @click="router.push({ name: 'app-submitted' })">
      View submitted forms
    </button>
  </section>
</template>

<style scoped>
.success-card {
  padding: 2rem 1.25rem;
  text-align: center;
  border: 1px solid #bbf7d0;
  border-radius: 1rem;
  background: white;
}

.success-icon {
  display: grid;
  width: 4rem;
  height: 4rem;
  margin: 0 auto 1rem;
  place-items: center;
  border-radius: 50%;
  background: #dcfce7;
  color: #15803d;
  font-size: 2rem;
}

h2 { margin: 0 0 .5rem; }
p { color: #4b5563; }
.receipt { font-family: monospace; overflow-wrap: anywhere; }

button {
  margin-top: .75rem;
  border: 0;
  border-radius: .5rem;
  background: #2563eb;
  color: white;
  padding: .75rem 1rem;
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}
</style>
