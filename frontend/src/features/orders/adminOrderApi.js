export const adminOrderApi = api => ({
  list: (page = 0, status = '', query = '', signal) => api.request(`/admin/orders?${new URLSearchParams({ page, size: 10, status, query })}`, { signal }),
  detail: (id, signal) => api.request(`/admin/orders/${encodeURIComponent(id)}`, { signal }),
  transition: (id, fromStatus, toStatus, note) => api.request(`/admin/orders/${encodeURIComponent(id)}/transitions`, { method: 'POST', body: { fromStatus, toStatus, note } }),
  note: (id, internalNote, expectedInternalNote) => api.request(`/admin/orders/${encodeURIComponent(id)}/internal-note`, { method: 'PATCH', body: { internalNote, expectedInternalNote } }),
});
