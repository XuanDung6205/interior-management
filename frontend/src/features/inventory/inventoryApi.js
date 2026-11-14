export const inventoryApi = api => ({
  warehouses: () => api.request('/admin/inventory/warehouses'),
  variants: query => api.request(`/commerce/catalog/variants?${new URLSearchParams({ query, page: 0, size: 50 })}`),
  receipts: page => api.request(`/admin/inventory/receipts?${new URLSearchParams({ page, size: 20 })}`),
  receive: (body, key) => api.request('/admin/inventory/receipts', {
    method: 'POST', body, headers: { 'Idempotency-Key': key },
  }),
  balance: (warehouseId, variantId) => api.request(`/admin/inventory/balances?${new URLSearchParams({ warehouseId, variantId })}`),
  movements: page => api.request(`/admin/inventory/movements?${new URLSearchParams({ page, size: 20 })}`),
  adjust: (body, key) => api.request('/admin/inventory/adjustments', {
    method: 'POST', body, headers: { 'Idempotency-Key': key },
  }),
  transfer: (body, key) => api.request('/admin/inventory/transfers', {
    method: 'POST', body, headers: { 'Idempotency-Key': key },
  }),
  dispatchQueue: page => api.request(`/admin/inventory/orders?${new URLSearchParams({ page, size: 20 })}`),
  dispatchHolds: orderId => api.request(`/admin/inventory/orders/${orderId}/holds`),
  dispatch: (orderId, body, key) => api.request(`/admin/inventory/orders/${orderId}/dispatch`, {
    method: 'POST', body, headers: { 'Idempotency-Key': key },
  }),
  shipments: (page, status) => api.request(`/admin/inventory/shipments?${new URLSearchParams({ page, size: 20, ...(status ? { status } : {}) })}`),
  shipmentStatus: (shipmentId, body, key) => api.request(`/admin/inventory/shipments/${shipmentId}/status`, {
    method: 'POST', body, headers: { 'Idempotency-Key': key },
  }),
});
