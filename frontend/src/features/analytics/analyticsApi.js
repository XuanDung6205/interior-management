// API bảng điều khiển thống kê quản trị và điều khiển cache TV5
export const analyticsApi = api => ({
  summary: (days = 30, signal) =>
    api.request(`/admin/dashboard/summary?${new URLSearchParams({ days })}`, { signal }),
  clearCache: () => api.request('/admin/dashboard/cache/clear', { method: 'POST' }),
});
