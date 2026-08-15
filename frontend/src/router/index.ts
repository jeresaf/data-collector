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
import AppFormsView from '@/views/AppFormsView.vue'
import AppFormStartView from '@/views/AppFormStartView.vue'
import AppDraftsView from '@/views/AppDraftsView.vue'
import AppSubmittedView from '@/views/AppSubmittedView.vue'
import ReviewAnswersPage from '@/views/ReviewAnswersPage.vue'
import AppSessionSubmitView from '@/views/AppSessionSubmitView.vue'
import IdentityAdminView from '@/views/IdentityAdminView.vue'
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

    // User-facing form workflow shell.
    {
      path: '/app/forms',
      name: 'app-forms',
      component: AppFormsView,
      meta: { requiresAuth: true },
    },
    {
      path: '/app/forms/:formId/start',
      name: 'app-form-start',
      component: AppFormStartView,
      meta: { requiresAuth: true },
    },
    {
      path: '/app/sessions/:sessionId/fill',
      name: 'app-session-fill',
      component: WebFormView,
      meta: { requiresAuth: true },
    },
    {
      path: '/app/sessions/:sessionId/review',
      name: 'app-session-review',
      component: ReviewAnswersPage,
      meta: { requiresAuth: true },
    },
    {
      path: '/app/sessions/:sessionId/submit',
      name: 'app-session-submit',
      component: AppSessionSubmitView,
      meta: { requiresAuth: true },
    },
    {
      path: '/app/drafts',
      name: 'app-drafts',
      component: AppDraftsView,
      meta: { requiresAuth: true },
    },
    {
      path: '/app/submitted',
      name: 'app-submitted',
      component: AppSubmittedView,
      meta: { requiresAuth: true },
    },
    {
      path: '/admin/identity',
      name: 'identity-admin',
      component: IdentityAdminView,
      meta: { requiresAuth: true, roles: ['SYSTEM_ADMIN'] },
    },

    // Compatibility routes retained for existing links/tests.
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
