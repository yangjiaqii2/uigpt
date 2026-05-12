import http from './http'

export function fetchConversations() {
  return http.get('/conversations')
}

export function fetchConversationMessages(id) {
  return http.get(`/conversations/${id}/messages`)
}

export function patchConversationTitle(id, title) {
  return http.patch(`/conversations/${id}`, { title })
}

export function patchConversationPinned(id, pinned) {
  return http.patch(`/conversations/${id}/pinned`, { pinned })
}

export function deleteConversation(id) {
  return http.delete(`/conversations/${id}`)
}
