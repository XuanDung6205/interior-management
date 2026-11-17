import { Link } from 'react-router-dom';
import { Icon } from '../../components/ui';
import { formatDate, formatMoney } from '../../lib/format';
import { paymentMethods, Status } from '../orders/OrderParts';

export const paymentStatuses = { PENDING: 'Đang chờ', SUCCEEDED: 'Đã thu tiền', FAILED: 'Thất bại', CANCELLED: 'Đã hủy', EXPIRED: 'Đã hết hạn' };
export const allPaymentMethods = { ...paymentMethods, GATEWAY: 'Cổng thanh toán' };
export function createBlockMessage(code) {
  const messages = {
    PAYMENT_PENDING_EXISTS: 'Đang có yêu cầu thanh toán chờ xử lý.',
    PAYMENT_NOT_REQUIRED: 'Đơn hàng không phát sinh khoản phải thu.',
    ORDER_NOT_PAYABLE: 'Trạng thái đơn hiện tại không cho phép tạo thêm yêu cầu thanh toán.',
    ORDER_ALREADY_PAID: 'Đơn hàng đã được thu đủ tiền.',
    REFUND_RECONCILIATION_REQUIRED: 'Khoản hoàn tiền cần được đối soát trước khi tạo yêu cầu mới.',
    PAYMENT_BANK_NOT_CONFIGURED: 'Cửa hàng chưa cấu hình hướng dẫn chuyển khoản.',
  };
  return Object.hasOwn(messages, code) ? messages[code] : 'Chưa thể tạo thêm yêu cầu thanh toán. Hãy làm mới để kiểm tra trạng thái đơn.';
}
export function PaymentNotice({ bank }) {
  const demo = bank?.isDemo || bank?.mode === 'DEMO';
  return <section className={`notice payment-mode-notice ${demo ? 'warning' : ''}`} aria-label="Phạm vi thanh toán thủ công"><Icon name="shield" size={24} /><div><h2>{demo ? 'DEMO — không chuyển tiền thật' : bank?.mode === 'MANUAL' ? 'Xác minh thủ công theo chứng từ' : 'Thu tiền thủ công · Bản học tập/demo'}</h2><p>{bank?.notice || 'Trong bản học tập/demo, chỉ dùng chứng từ giả và không chuyển tiền thật.'}</p><p>Trang này không gọi ngân hàng và không phải sandbox cổng thanh toán. Chọn phương thức hay tạo yêu cầu chưa có nghĩa là đã thu tiền.</p></div></section>;
}
export function PaymentAmounts({ summary }) {
  return <section className="panel payment-amounts" aria-label="Tổng hợp tiền thanh toán"><div className="panel-heading"><h2>Tổng hợp thanh toán</h2><Status value={summary.orderStatus} /></div><div className="payment-amounts-body"><dl className="money-breakdown"><div><dt>Giá trị đơn hàng</dt><dd>{formatMoney(summary.totalAmount)}</dd></div><div><dt>Đã thu trước hoàn</dt><dd>{formatMoney(summary.collectedAmount)}</dd></div><div className="summary-total"><dt>Còn phải thu</dt><dd data-testid="payment-remaining">{formatMoney(summary.remainingAmount)}</dd></div></dl><div className="refund-amounts"><h3>Theo dõi hoàn tiền riêng</h3><dl><div><dt>Đang chờ hoàn</dt><dd>{formatMoney(summary.pendingRefundAmount)}</dd></div><div><dt>Đã hoàn</dt><dd>{formatMoney(summary.refundedAmount)}</dd></div><div><dt>Thu ròng sau hoàn</dt><dd>{formatMoney(summary.netCollectedAmount)}</dd></div></dl><p className="muted small">Yêu cầu hoàn đang chờ không xác nhận tiền đã được trả lại. Hoàn tiền không mở lại khoản phải thu của đơn cũ.</p></div></div></section>;
}
export function PaymentHistory({ items, staff = false, selectedId }) {
  return <section className="panel payment-history" aria-label="Lịch sử thanh toán"><div className="panel-heading"><h2>Lịch sử thanh toán</h2></div>{items.length ? items.map(payment => <article key={payment.id} className={`payment-history-record ${String(payment.id) === String(selectedId) ? 'selected-payment' : ''}`}><div className="payment-history-top"><div><span className="eyebrow">KHOẢN #{payment.id}</span><h3>{allPaymentMethods[payment.method] || payment.method}</h3><strong>{formatMoney(payment.amount)}</strong></div><Status value={payment.status} /></div><dl className="payment-receipt-meta"><div><dt>Ngày tạo</dt><dd>{formatDate(payment.createdAt)}</dd></div>{payment.reference && <div><dt>Chứng từ / tham chiếu</dt><dd>{payment.reference}</dd></div>}{payment.paidAt && <div><dt>Đã xác minh thu lúc</dt><dd>{formatDate(payment.paidAt)}</dd></div>}{payment.expiresAt && <div><dt>Hạn yêu cầu</dt><dd>{formatDate(payment.expiresAt)}</dd></div>}{payment.isExpired && <div><dt>Hiệu lực</dt><dd>Yêu cầu đã hết hạn theo giờ máy chủ.</dd></div>}{staff && payment.verifiedBy && <div><dt>Nhân viên xác minh</dt><dd>{payment.verifiedBy.fullName || `#${payment.verifiedBy.id}`}</dd></div>}{staff && payment.confirmationNote && <div><dt>Ghi chú xác minh nội bộ</dt><dd>{payment.confirmationNote}</dd></div>}</dl>{staff && <Link className="inline-link" to={`/admin/payments/${payment.id}`}>Xem khoản #{payment.id}</Link>}</article>) : <p className="empty-payment-history">Chưa có yêu cầu thanh toán.</p>}</section>;
}
export function BankGuidance({ summary }) {
  const bank = summary.bankTransfer;
  const active = summary.items.some(item => item.method === 'BANK_TRANSFER' && item.status === 'PENDING' && !item.isExpired);
  if (!active) return null;
  return <section className="panel bank-guidance"><div className="panel-heading"><h2>Hướng dẫn chuyển khoản</h2></div><div className="order-section-body">{bank?.configured ? <><p className="muted small">{bank.isDemo ? 'Thông tin minh họa không dùng để chuyển tiền thật.' : 'Đối chiếu thông tin người nhận do cửa hàng cung cấp.'}</p><dl className="payment-receipt-meta"><div><dt>Ngân hàng</dt><dd>{bank.bankName}</dd></div><div><dt>Số tài khoản</dt><dd className="bank-account">{bank.accountNumber}</dd></div><div><dt>Chủ tài khoản</dt><dd>{bank.accountHolder}</dd></div><div><dt>Nội dung</dt><dd>{bank.transferContent}</dd></div></dl><p className="muted small">Trạng thái chỉ đổi sau khi nhân viên kiểm tra chứng từ. Không tạo thêm yêu cầu trong khi khoản hiện tại còn chờ.</p></> : <div className="notice warning">{bank?.notice || 'Chưa cấu hình thông tin ngân hàng. Liên hệ cửa hàng để được hướng dẫn.'}</div>}</div></section>;
}
