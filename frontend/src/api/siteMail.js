import http from './http'

export function fetchSiteMailSummary() {
  return http.get('/me/site-mail/summary')
}

export function fetchSiteMailThread() {
  return http.get('/me/site-mail/thread')
}

/**
 * @param {string} bodyText
 * @param {File[]} imageFiles
 */
export function sendSiteMailMessage(bodyText, imageFiles) {
  const fd = new FormData()
  fd.append('body', bodyText ?? '')
  for (const f of imageFiles || []) {
    if (f) fd.append('images', f)
  }
  return http.post('/me/site-mail/messages', fd, {
    headers: { 'Content-Type': undefined },
  })
}

export function fetchAdminSiteMailUnread() {
  return http.get('/admin/site-mail/unread-count')
}

export function fetchAdminSiteMailThreads(params) {
  return http.get('/admin/site-mail/threads', { params })
}

export function fetchAdminSiteMailThread(threadId) {
  return http.get(`/admin/site-mail/threads/${threadId}`)
}

/**
 * @param {number|string} threadId
 * @param {string} bodyText
 * @param {File[]} imageFiles
 */
export function sendAdminSiteMailReply(threadId, bodyText, imageFiles) {
  const fd = new FormData()
  fd.append('body', bodyText ?? '')
  for (const f of imageFiles || []) {
    if (f) fd.append('images', f)
  }
  return http.post(`/admin/site-mail/threads/${threadId}/messages`, fd, {
    headers: { 'Content-Type': undefined },
  })
}
