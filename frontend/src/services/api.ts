import axios from 'axios'

export const ACCESS_TOKEN_STORAGE_KEY = 'odk_access_token'
export const REFRESH_TOKEN_STORAGE_KEY = 'odk_refresh_token'
export const AUTH_USER_STORAGE_KEY = 'odk_auth_user'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080/api',
  headers: {
    'Content-Type': 'application/json',
  },
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem(ACCESS_TOKEN_STORAGE_KEY)

  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }

  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem(ACCESS_TOKEN_STORAGE_KEY)
      localStorage.removeItem(REFRESH_TOKEN_STORAGE_KEY)
      localStorage.removeItem(AUTH_USER_STORAGE_KEY)

      if (window.location.pathname !== '/login') {
        const redirect =
          `${window.location.pathname}${window.location.search}${window.location.hash}`

        window.location.href = `/login?redirect=${encodeURIComponent(redirect)}`
      }
    }

    return Promise.reject(error)
  },
)

export default api
