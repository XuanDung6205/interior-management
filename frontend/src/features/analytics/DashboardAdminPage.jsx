import { useEffect, useMemo, useState } from 'react';
import { ErrorNotice, Loading } from '../../components/ui';
import { formatMoney } from '../../lib/format';
import { useCommerce } from '../../lib/session';
import { analyticsApi } from './analyticsApi';

const ORDER_STATUS_LABELS = {
  PENDING_CONFIRMATION: 'Chờ xác nhận',
  CONFIRMED: 'Đã xác nhận',
  PROCESSING: 'Đang xử lý',
  COMPLETED: 'Hoàn tất',
  CANCELLED: 'Đã hủy',
};

const FUNNEL_LABELS = {
  PAGE_VIEW: 'Lượt xem trang (client)',
  PRODUCT_VIEW: 'Xem sản phẩm (client)',
  SEARCH: 'Tìm kiếm (client)',
  ADD_TO_CART_CLICK: 'Bấm thêm giỏ (client)',
  ADD_TO_CART: 'Thêm giỏ thành công (server)',
  CHECKOUT_START: 'Vào trang đặt hàng (client)',
  ORDER_PLACED: 'Đặt đơn thành công (server)',
};

// Trang Dashboard doanh thu thực và vận hành bán hàng dành cho nhân viên/admin (route /admin/dashboard)
export default function DashboardAdminPage() {
  const { api } = useCommerce();
  const client = useMemo(() => analyticsApi(api), [api]);
  const [days, setDays] = useState(30);
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState('');
  const [clearing, setClearing] = useState(false);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    setData(null);
    client.summary(days, controller.signal)
      .then(setData)
      .catch(err => { if (err.name !== 'AbortError') setError(err); });
    return () => controller.abort();
  }, [client, days, revision]);

  async function flushCache() {
    if (clearing) return;
    setClearing(true);
    setError(null);
    setNotice('');
    try {
      await client.clearCache();
      setNotice('Đã xóa cache TV5 và tải lại số liệu mới từ cơ sở dữ liệu.');
      setRevision(v => v + 1);
    } catch (err) {
      setError(err);
    } finally {
      setClearing(false);
    }
  }

  return <>
    <div className="breadcrumb"><span>Quản trị</span><span>/</span><span>Thống kê</span></div>
    <div className="page-heading">
      <div>
        <span className="eyebrow">PHÂN TÍCH & DOANH THU THỰC</span>
        <h1>Bảng điều khiển<span className="heading-dot">.</span></h1>
        <p>Doanh thu ròng tính từ khoản thu thành công trừ khoản hoàn tiền thành công, không cộng đơn chưa thu.</p>
      </div>
      <div className="tv5-row-actions">
        {[7, 30, 90].map(range => (
          <button
            key={range}
            className={days === range ? 'button' : 'button secondary'}
            onClick={() => setDays(range)}
          >{range} ngày</button>
        ))}
      </div>
    </div>

    <ErrorNotice error={error} onRetry={() => setRevision(v => v + 1)} />
    {notice && <div className="notice success" role="status">{notice}</div>}
    {!data && !error && <Loading text="Đang tổng hợp số liệu…" />}

    {data && (
      <div className="aftercare-stack">
        <section className="panel order-section-body">
          <h2>Tổng quan tài chính ({data.days} ngày gần nhất)</h2>
          <dl className="operations-metrics">
            <div><dt>Doanh thu ròng</dt><dd>{formatMoney(data.revenue.netRevenue)}</dd></div>
            <div><dt>Tổng thu thành công</dt><dd>{formatMoney(data.revenue.grossCollected)}</dd></div>
            <div><dt>Đã hoàn tiền thành công</dt><dd>{formatMoney(data.revenue.totalRefunded)}</dd></div>
            <div><dt>Tổng đơn phát sinh</dt><dd>{data.totalOrders}</dd></div>
          </dl>
        </section>

        <section className="panel order-section-body">
          <h2>Đơn hàng theo trạng thái</h2>
          <dl className="operations-metrics">
            {Object.entries(data.ordersByStatus).map(([key, count]) => (
              <div key={key}>
                <dt>{ORDER_STATUS_LABELS[key] || key}</dt>
                <dd>{count}</dd>
              </div>
            ))}
          </dl>
        </section>

        <section className="panel order-section-body">
          <h2>Top sản phẩm bán chạy</h2>
          {data.topProducts.length ? (
            <div className="tv5-table-wrap">
              <table className="tv5-table">
                <thead>
                  <tr><th>Sản phẩm</th><th>Số lượng bán</th><th>Giá trị hàng</th></tr>
                </thead>
                <tbody>
                  {data.topProducts.map(item => (
                    <tr key={item.productId}>
                      <td>{item.productName}</td>
                      <td>{item.unitsSold}</td>
                      <td>{formatMoney(item.grossMerchandise)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : <p className="muted">Chưa có đơn hàng hợp lệ trong khoảng thời gian này.</p>}
        </section>

        <section className="panel order-section-body">
          <h2>Cảnh báo tồn kho thấp (≤ 5 sản phẩm)</h2>
          {data.lowStockVariants.length ? (
            <div className="tv5-table-wrap">
              <table className="tv5-table">
                <thead>
                  <tr><th>Kho</th><th>SKU</th><th>Sản phẩm</th><th>Tồn khả dụng</th></tr>
                </thead>
                <tbody>
                  {data.lowStockVariants.map(item => (
                    <tr key={`${item.warehouseId}-${item.variantId}`}>
                      <td>{item.warehouseCode}</td>
                      <td>{item.sku}</td>
                      <td>{item.productName} — {item.variantName}</td>
                      <td><strong>{item.availableQuantity}</strong></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : <p className="muted">Không có SKU nào dưới ngưỡng tồn thấp.</p>}
        </section>

        <section className="panel order-section-body">
          <h2>Phễu hành vi người dùng (tracking_events)</h2>
          <dl className="operations-metrics">
            {Object.entries(data.funnel).map(([step, count]) => (
              <div key={step}>
                <dt>{FUNNEL_LABELS[step] || step}</dt>
                <dd>{count}</dd>
              </div>
            ))}
          </dl>
        </section>

        {data.cacheStats && (
          <section className="panel order-section-body">
            <div className="panel-heading" style={{ padding: '0 0 14px', borderBottom: '1px solid var(--line)', marginBottom: '16px' }}>
              <h2>Hiệu năng Cache TV5</h2>
              <button className="button secondary" disabled={clearing} onClick={flushCache}>
                {clearing ? 'Đang xóa cache…' : 'Xóa cache TV5'}
              </button>
            </div>
            <dl className="operations-metrics">
              <div><dt>Trạng thái</dt><dd>{data.cacheStats.enabled ? 'Đang bật' : 'Đang tắt'}</dd></div>
              <div><dt>Cache hits</dt><dd>{data.cacheStats.hits}</dd></div>
              <div><dt>Cache misses</dt><dd>{data.cacheStats.misses}</dd></div>
              <div><dt>Tỷ lệ hit</dt><dd>{data.cacheStats.hitRatePercent}%</dd></div>
            </dl>
          </section>
        )}
      </div>
    )}
  </>;
}
