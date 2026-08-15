import {
  createRouter,
  createWebHistory,
  type NavigationGuardReturn,
  type RouteLocationNormalized,
} from 'vue-router'
import ProjectsView from '@/views/ProjectsView.vue'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import FormDetailView from '@/views/FormDetailView.vue'
import SubmissionsView from '@/views/SubmissionsView.vue'
import SubmissionDetailView from '@/views/SubmissionDetailView.vue'
import WebFormView from '@/views/WebFormView.vue'
import LoginView from '@/views/LoginView.vue'
import { getStoredAuthUser, hasStoredAccessToken } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    roles?: string[]
    permissions?: string[]
  }
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
    },
    {
      path: '/',
      name: 'projects',
      component: ProjectsView,
      meta: { requiresAuth: true },
    },
    {
      path: '/projects/:id',
      name: 'project-detail',
      component: ProjectDetailView,
      meta: { requiresAuth: true },
    },
    {
      path: '/projects/:projectId/forms/:formId',
      name: 'form-detail',
      component: FormDetailView,
      meta: { requiresAuth: true },
    },
    {
      path: '/submissions',
      name: 'submissions',
      component: SubmissionsView,
      meta: { requiresAuth: true },
    },
    {
      path: '/submissions/:id',
      name: 'submission-detail',
      component: SubmissionDetailView,
      meta: { requiresAuth: true },
    },
    {
      path: '/forms/:formVersionId/web',
      name: 'web-form-new',
      component: WebFormView,
      meta: { requiresAuth: true },
    },
    {
      path: '/forms/:formVersionId/web/:sessionId',
      name: 'web-form-resume',
      component: WebFormView,
      meta: { requiresAuth: true },
    },
  ],
})

export function authGuard(
  to: RouteLocationNormalized,
): NavigationGuardReturn {
  if (!to.meta.requiresAuth) {
    return true
  }

  if (!hasStoredAccessToken()) {
    return {
      name: 'login',
      query: { redirect: to.fullPath },
    }
  }

  const user = getStoredAuthUser()
  const requiredRoles = to.meta.roles ?? []
  const requiredPermissions = to.meta.permissions ?? []

  if (
    requiredRoles.length > 0 &&
    (!user || !requiredRoles.some((role) => user.roles.includes(role)))
  ) {
    return { name: 'projects' }
  }

  if (
    requiredPermissions.length > 0 &&
    (!user ||
      !requiredPermissions.every((permission) =>
        user.permissions.includes(permission),
      ))
  ) {
    return { name: 'projects' }
  }

  return true
}

router.beforeEach(authGuard)

export default router
