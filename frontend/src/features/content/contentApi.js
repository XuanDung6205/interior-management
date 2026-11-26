// Gom các hàm gọi REST API của module Content, nhận apiClient từ SessionContext
export const contentApi = api => ({
  // API công khai cho trang chủ và trang bài viết
  publicBanners: (placement = 'HOME_HERO', signal) =>
    api.request(`/commerce/content/banners?${new URLSearchParams({ placement })}`, { signal }),
  publicArticles: (query = '', page = 0, signal) =>
    api.request(`/commerce/content/articles?${new URLSearchParams({ query, page, size: 9 })}`, { signal }),
  publicArticle: (slug, signal) =>
    api.request(`/commerce/content/articles/${encodeURIComponent(slug)}`, { signal }),

  // API quản trị banner
  adminBanners: (query = '', placement = '', page = 0, signal) =>
    api.request(`/admin/content/banners?${new URLSearchParams({ query, placement, page, size: 20 })}`, { signal }),
  createBanner: body => api.request('/admin/content/banners', { method: 'POST', body }),
  updateBanner: (id, body) => api.request(`/admin/content/banners/${id}`, { method: 'PUT', body }),
  deleteBanner: id => api.request(`/admin/content/banners/${id}`, { method: 'DELETE' }),

  // API quản trị bài viết
  adminArticles: (query = '', status = '', page = 0, signal) =>
    api.request(`/admin/content/articles?${new URLSearchParams({ query, status, page, size: 20 })}`, { signal }),
  adminArticle: (id, signal) => api.request(`/admin/content/articles/${id}`, { signal }),
  createArticle: body => api.request('/admin/content/articles', { method: 'POST', body }),
  updateArticle: (id, body) => api.request(`/admin/content/articles/${id}`, { method: 'PUT', body }),
  deleteArticle: id => api.request(`/admin/content/articles/${id}`, { method: 'DELETE' }),
});
