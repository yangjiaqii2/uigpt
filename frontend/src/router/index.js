import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: () => import('../layouts/ModuleLayout.vue'),
      children: [
        { path: '', redirect: { name: 'chat' } },
        {
          path: 'chat',
          name: 'chat',
          component: () => import('../views/ChatView.vue'),
          meta: { public: true },
        },
        {
          path: 'image-gen',
          name: 'image-gen',
          component: () => import('../views/ImageGenView.vue'),
          meta: { public: true },
        },
        {
          path: 'video-gen',
          name: 'video-gen',
          component: () => import('../views/VideoGenView.vue'),
          meta: { public: true },
        },
        {
          path: 'system',
          redirect: { name: 'knowledge' },
        },
        {
          path: 'knowledge',
          name: 'knowledge',
          component: () => import('../views/KnowledgeBaseView.vue'),
          meta: { requiresAuth: true, requiresSuperAdmin: true },
        },
        {
          path: 'prompts',
          name: 'prompts',
          component: () => import('../views/PromptsView.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: 'skill-plaza',
          name: 'skill-plaza',
          component: () => import('../views/SkillPlaza.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: 'admin/users',
          name: 'admin-users',
          component: () => import('../views/UserAdminView.vue'),
          meta: { requiresAuth: true, requiresAdmin: true },
        },
        {
          path: 'admin/site-mail',
          name: 'admin-site-mail',
          component: () => import('../views/AdminSiteMailView.vue'),
          meta: { requiresAuth: true, requiresAdmin: true },
        },
        {
          path: 'history',
          name: 'history',
          component: () => import('../views/HistoryView.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: 'works',
          name: 'works',
          component: () => import('../views/MyWorksView.vue'),
          meta: { requiresAuth: true },
        },
        {
          path: 'studio-works',
          name: 'studio-works',
          component: () => import('../views/StudioWorksLibraryView.vue'),
          meta: { requiresAuth: true },
        },
      ],
    },
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('../views/RegisterView.vue'),
      meta: { public: true },
    },
    {
      path: '/forgot-password',
      name: 'forgot-password',
      component: () => import('../views/ForgotPasswordView.vue'),
      meta: { public: true },
    },
    { path: '/:pathMatch(.*)*', redirect: { name: 'chat' } },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  const authPages = ['/login', '/register', '/forgot-password']
  if (authPages.includes(to.path)) {
    if (
      auth.isAuthenticated &&
      (to.path === '/login' || to.path === '/register' || to.path === '/forgot-password')
    ) {
      return { path: '/chat' }
    }
    return true
  }

  const isPublic = to.matched.some((r) => r.meta.public === true)
  if (isPublic) {
    return true
  }

  if (!auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (to.matched.some((r) => r.meta.requiresAdmin === true)) {
    await auth.refreshMe()
    if (!auth.isAdmin) {
      return { path: '/chat', query: {} }
    }
  }

  if (to.matched.some((r) => r.meta.requiresSuperAdmin === true)) {
    await auth.refreshMe()
    if (!auth.isSuperAdmin) {
      return { path: '/chat', query: {} }
    }
  }

  return true
})

export default router
