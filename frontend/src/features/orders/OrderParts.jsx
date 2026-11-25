import { Icon, ProductImage } from '../../components/ui';
import { formatMoney } from '../../lib/format';

export const orderStates = { PENDING_CONFIRMATION: 'Chờ xác nhận', CONFIRMED: 'Đã xác nhận', PROCESSING: 'Đang xử lý', COMPLETED: 'Hoàn thành', CANCELLED: 'Đã hủy' };
const states = { ...orderStates, PENDING: 'Đang chờ', SUCCEEDED: 'Đã thu tiền', FAILED: 'Thất bại', REFUNDED: 'Đã hoàn tiền', PARTIALLY_REFUNDED: 'Đã hoàn một phần', CREATED: 'Đã tạo', READY: 'Sẵn sàng giao', SHIPPED: 'Đang giao', DELIVERED: 'Đã giao', RETURNED: 'Đã trả hàng', IN_TRANSIT: 'Đang vận chuyển', OUT_FOR_DELIVERY: 'Đang giao tới bạn', PACKING: 'Đang đóng gói', NOT_CREATED: 'Chưa tạo', NOT_REQUIRED: 'Không yêu cầu', PARTIALLY_PAID: 'Đã thu một phần', EXCEPTION: 'Cần kiểm tra', EXPIRED: 'Đã hết hạn' };
export const paymentMethods = { COD: 'Thanh toán khi nhận hàng', BANK_TRANSFER: 'Chuyển khoản ngân hàng', SEPAY: 'SePay Sandbox · QR thử nghiệm' };
export function Status({ value, kind }) {
  const label = kind === 'refund' && value === 'SUCCEEDED' ? 'Đã hoàn tiền' : value ? states[value] || value : 'Chưa có';
  return <span className={`status-pill ${['CANCELLED', 'FAILED', 'EXPIRED', 'NOT_CREATED', 'NOT_REQUIRED', 'EXCEPTION'].includes(value) ? 'inactive' : 'active'}`}><span />{label}</span>;
}
export function AddressSnapshot({ address }) {
  if (!address) return <p className="muted">Chưa có địa chỉ giao hàng.</p>;
  return <address className="address-snapshot"><strong>{address.recipientName}</strong><span>{address.phone}{address.email ? ` · ${address.email}` : ''}</span><span>{[address.addressLine1, address.addressLine2, address.wardName, address.districtName, address.provinceName, address.postalCode].filter(Boolean).join(', ')}</span><span>{address.countryCode === 'VN' ? 'Việt Nam' : address.countryCode}</span>{address.companyName && <span>{address.companyName}</span>}{address.taxCode && <span>MST: {address.taxCode}</span>}</address>;
}
function snapshotText(value) {
  if (value == null) return null;
  if (typeof value === 'string') { try { return snapshotText(JSON.parse(value)); } catch { return value; } }
  if (Array.isArray(value)) return value.map(snapshotText).join(', ');
  if (typeof value === 'object') {
    const labels = { width: 'Rộng', height: 'Cao', depth: 'Sâu', length: 'Dài', unit: 'Đơn vị', material: 'Chất liệu', name: 'Tên' };
    return Object.entries(value).map(([key, item]) => `${labels[key] || key}: ${snapshotText(item)}`).join(' · ');
  }
  return String(value);
}
export function OrderItems({ items, snapshots = false }) {
  return <div className="order-items">{items.map((item, index) => <article className="order-item" key={item.id ?? `${item.variantId}-${index}`}><ProductImage src={item.imageUrl} name={item.productName} /><div className="order-item-info"><span className="sku">{item.sku}</span><h3>{item.productName}</h3><p className="muted">{item.variantName}{item.colorName ? ` · ${item.colorName}` : ''}</p><p>{item.quantity} × {formatMoney(item.unitPrice)}</p>{snapshots && <dl className="snapshot-specs">{item.dimensionsSnapshot && <div><dt>Kích thước</dt><dd>{snapshotText(item.dimensionsSnapshot)}</dd></div>}{item.materialsSnapshot && <div><dt>Chất liệu</dt><dd>{snapshotText(item.materialsSnapshot)}</dd></div>}{item.warrantyMonths != null && <div><dt>Bảo hành</dt><dd>{item.warrantyMonths} tháng</dd></div>}<div><dt>Yêu cầu kỹ thuật lắp đặt</dt><dd>{item.assemblyRequired ? 'Cần lắp đặt theo kỹ thuật.' : 'Không cần lắp đặt theo kỹ thuật.'}</dd></div></dl>}</div><strong className="order-item-total">{formatMoney(item.lineTotal)}</strong></article>)}</div>;
}
export function MoneyBreakdown({ value, testId }) {
  return <dl className="money-breakdown"><div><dt>Tiền hàng</dt><dd>{formatMoney(value.subtotal)}</dd></div><div><dt>Ưu đãi{value.couponCode ? ` · ${value.couponCode}` : ''}</dt><dd>{formatMoney(value.discountAmount)}</dd></div><div><dt>Giao hàng</dt><dd>{formatMoney(value.shippingFee)}</dd></div><div><dt>Lắp đặt</dt><dd>{formatMoney(value.assemblyFee)}</dd></div><div><dt>Thuế bổ sung</dt><dd>{formatMoney(value.taxAmount)}</dd></div><div className="summary-total"><dt>Tổng cộng</dt><dd data-testid={testId}>{formatMoney(value.grandTotal)}</dd></div></dl>;
}
export function OrderNote({ children }) { return <p className="order-note"><Icon name="shield" size={18} /><span>{children}</span></p>; }
export function AssemblySelection({ requested }) {
  return <section className="panel address-panel"><h2>Dịch vụ lắp đặt</h2><p className="assembly-selection">{requested ? 'Có chọn dịch vụ lắp đặt.' : 'Không chọn dịch vụ lắp đặt.'}</p><p className="muted small">Yêu cầu kỹ thuật của từng sản phẩm được hiển thị riêng trong thông tin sản phẩm.</p></section>;
}
