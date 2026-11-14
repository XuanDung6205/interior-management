export const inventoryApi = api => ({
  warehouses: () => api.request('/admin/inventory/warehouses'),
  variants: query => api.request(`/commerce/catalog/variants?${new URLSearchParams({ query, page: 0, size: 50 })}`),
  receipts: page => api.request(`/admin/inventory/receipts?${new URLSearchParams({ page, size: 20 })}`),
  receive: (body, key) => api.request('/admin/inventory/receipts', {
    method: 'POST', body, headers: { 'Idempotency-Key': key },
  }),
});
