const SESSION_KEY = 'nepnha.trackingSessionId';

// Tạo hoặc lấy lại UUID phiên tracking trong sessionStorage của trình duyệt
export function getTrackingSessionId() {
  try {
    let id = sessionStorage.getItem(SESSION_KEY);
    if (!id || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) {
      id = typeof crypto !== 'undefined' && crypto.randomUUID
        ? crypto.randomUUID()
        : '00000000-0000-4000-8000-000000000000';
      sessionStorage.setItem(SESSION_KEY, id);
    }
    return id;
  } catch {
    return '00000000-0000-4000-8000-000000000000';
  }
}

// Gửi tín hiệu hành vi lên server; nuốt lỗi mạng để không làm gián đoạn trải nghiệm mua sắm
export function trackEvent(api, payload) {
  return api.request('/tracking/events', {
    method: 'POST',
    body: {
      ...payload,
      sessionId: getTrackingSessionId(),
      occurredAt: new Date().toISOString(),
    },
  }).catch(() => {});
}
