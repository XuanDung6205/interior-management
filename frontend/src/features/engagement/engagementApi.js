// Gom API danh sách yêu thích (wishlist) và đánh giá sản phẩm (reviews)
export const engagementApi = api => ({
  wishlist: signal => api.request('/wishlist', { signal }),
  addWishlist: productId => api.request(`/wishlist/${productId}`, { method: 'POST' }),
  removeWishlist: productId => api.request(`/wishlist/${productId}`, { method: 'DELETE' }),

  productReviews: (productId, page = 0, signal) =>
    api.request(`/commerce/products/${productId}/reviews?${new URLSearchParams({ page, size: 10 })}`, { signal }),
  eligibleReviews: signal => api.request('/reviews/eligible', { signal }),
  myReviews: signal => api.request('/reviews/mine', { signal }),
  createReview: body => api.request('/reviews', { method: 'POST', body }),

  adminReviews: (status = '', query = '', page = 0, signal) =>
    api.request(`/admin/reviews?${new URLSearchParams({ status, query, page, size: 20 })}`, { signal }),
  moderateReview: (id, body) => api.request(`/admin/reviews/${id}/moderate`, { method: 'PATCH', body }),
});
