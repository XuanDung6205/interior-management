export const userAdminApi = api => ({
  list: ({ query, role, status, page = 0, signal }) => api.request(
    `/admin/users?${new URLSearchParams({ query, role, status, page, size: 20 })}`,
    { signal },
  ),
  setStatus: (id, status) => api.request(`/admin/users/${id}/status`, {
    method: 'PATCH',
    body: { status },
  }),
  setRole: (id, roleCode) => api.request(`/admin/users/${id}/role`, {
    method: 'PATCH',
    body: { roleCode },
  }),
});
