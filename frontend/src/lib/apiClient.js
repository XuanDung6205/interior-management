export class ApiError extends Error {
  constructor(status, data) {
    super(data?.message || `Không thể hoàn thành yêu cầu (${status}). Vui lòng thử lại.`);
    this.status = status;
    this.code = data?.code;
    this.fieldErrors = data?.fieldErrors || {};
    this.quote = data?.quote;
  }
}

export function createApiClient(fetcher = (...args) => fetch(...args)) {
  let csrf;
  let csrfPromise;
  async function request(path, { method = 'GET', body, signal, cache, form = false, headers: extraHeaders = {} } = {}) {
    const headers = { Accept: 'application/json', ...extraHeaders };
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      const token = csrf || await refreshCsrf();
      headers[token.headerName] = token.token;
    }
    if (body !== undefined) headers['Content-Type'] = form ? 'application/x-www-form-urlencoded;charset=UTF-8' : 'application/json';
    let response;
    try {
      response = await fetcher(`/api${path}`, { method, credentials: 'include', headers, signal, ...(cache ? { cache } : {}), ...(body !== undefined ? { body: form ? new URLSearchParams(body).toString() : JSON.stringify(body) } : {}) });
    } catch (error) {
      if (error.name === 'AbortError') throw error;
      throw new ApiError(0, { code: 'NETWORK_ERROR', message: 'Chưa kết nối được máy chủ. Kiểm tra kết nối và thử lại.' });
    }
    let data = null;
    if (response.status !== 204) {
      try { data = await response.json(); }
      catch (error) {
        if (error.name === 'AbortError') throw error;
        // Headers can arrive after a mutation commits, while its body is lost.
        // Keep this outcome uncertain so callers retain the original key/proof.
        if (response.ok) throw new ApiError(response.status, { code: 'INVALID_RESPONSE', message: 'Chưa đọc được phản hồi từ máy chủ. Giữ nguyên yêu cầu đã gửi và thử lại để kiểm tra kết quả.' });
      }
    }
    if (!response.ok) {
      if (response.status === 403) csrf = undefined;
      throw new ApiError(response.status, data);
    }
    return data;
  }
  async function refreshCsrf() {
    if (!csrfPromise) csrfPromise = request('/csrf').then(value => { csrf = value; return value; }).finally(() => { csrfPromise = undefined; });
    return csrfPromise;
  }
  async function authenticate(path, body) {
    const session = await request(path, { method: 'POST', body, form: !!body });
    csrf = undefined;
    // Refresh failure must not misreport a successful login/logout as a failed one.
    // The next mutation will fetch it again and surface any persistent error.
    await refreshCsrf().catch(() => {});
    return session;
  }
  async function download(path, { signal } = {}) {
    let response;
    try { response = await fetcher(`/api${path}`, { method: 'GET', credentials: 'include', cache: 'no-store', headers: { Accept: 'text/csv' }, signal }); }
    catch (error) {
      if (error.name === 'AbortError') throw error;
      throw new ApiError(0, { code: 'NETWORK_ERROR', message: 'Chưa kết nối được máy chủ. Kiểm tra kết nối và thử lại.' });
    }
    if (!response.ok) {
      let data;
      try { data = await response.json(); } catch (error) { if (error.name === 'AbortError') throw error; }
      if (response.status === 403) csrf = undefined;
      throw new ApiError(response.status, data);
    }
    if (!/^text\/csv(?:;|$)/i.test(response.headers.get('Content-Type') || '')) throw new ApiError(response.status, { code: 'INVALID_RESPONSE', message: 'Máy chủ chưa trả về tệp CSV hợp lệ. Vui lòng đăng nhập và thử lại.' });
    const disposition = response.headers.get('Content-Disposition') || '';
    const candidate = /(?:^|;)\s*filename="([^"]+)"/i.exec(disposition)?.[1] || /(?:^|;)\s*filename=([^;\s]+)/i.exec(disposition)?.[1];
    const filename = candidate && /^orders-[A-Za-z0-9_-]+\.csv$/.test(candidate) ? candidate : 'orders.csv';
    return { blob: await response.blob(), filename };
  }
  return {
    request,
    download,
    login: (username, password) => authenticate('/session/login', { username, password }),
    logout: () => authenticate('/session/logout'),
    mergeCart: () => request('/cart/merge', { method: 'POST' }),
  };
}
