// API trợ lý tư vấn nội thất và hệ thống gợi ý sản phẩm
export const aiApi = api => ({
  recommendations: ({ productId, sessionId, limit = 6 } = {}, signal) => {
    const params = new URLSearchParams({ limit: String(limit) });
    if (productId) params.set('productId', String(productId));
    if (sessionId) params.set('sessionId', sessionId);
    return api.request(`/commerce/recommendations?${params}`, { signal });
  },
  conversations: signal => api.request('/commerce/chat', { signal }),
  conversation: (publicId, signal) => api.request(`/commerce/chat/${encodeURIComponent(publicId)}`, { signal }),
  sendMessage: (conversationId, message) =>
    api.request('/commerce/chat', { method: 'POST', body: { conversationId, message } }),
});
