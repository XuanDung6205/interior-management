import { Link } from 'react-router-dom';
import { Icon } from '../../components/ui';
import { useCommerce } from '../../lib/session';

const sections = [
  ['/admin/dashboard', 'Thống kê', 'Theo dõi doanh thu, đơn hàng, sản phẩm và tồn kho thấp.'],
  ['/admin/orders', 'Quản lý đơn hàng', 'Tra cứu đơn, cập nhật trạng thái và xử lý hậu mãi.'],
  ['/admin/payments', 'Khoản thu', 'Đối soát các khoản thanh toán của khách hàng.'],
  ['/admin/refunds', 'Hoàn tiền', 'Theo dõi và xử lý các yêu cầu hoàn tiền.'],
  ['/admin/coupons', 'Ưu đãi', 'Tạo, cập nhật và kiểm soát mã ưu đãi.'],
  ['/admin/content', 'Nội dung', 'Quản lý nội dung hiển thị trên cửa hàng.'],
  ['/admin/reviews', 'Duyệt đánh giá', 'Kiểm duyệt đánh giá sản phẩm của khách hàng.'],
  ['/admin/operations', 'Vận hành', 'Theo dõi và xử lý tác vụ vận hành hệ thống.'],
  ['/admin/inventory/receipts', 'Nhập kho', 'Ghi nhận hàng hóa nhập vào kho.'],
  ['/admin/inventory/operations', 'Điều chuyển', 'Điều chỉnh hoặc chuyển tồn giữa các kho.'],
  ['/admin/inventory/dispatch', 'Xuất kho', 'Xử lý xuất kho cho các đơn cần giao.'],
  ['/admin/inventory/shipments', 'Vận đơn', 'Theo dõi giao hàng và cập nhật kết quả.'],
];

export default function AdminOverviewPage() {
  const { isAdmin } = useCommerce();
  const visibleSections = isAdmin
    ? [...sections, ['/admin/users', 'Quản lý tài khoản', 'Tìm kiếm, phân quyền và khóa/mở tài khoản.']]
    : sections;

  return <>
    <div className="breadcrumb"><span>Quản trị</span><span>/</span><span>Tổng quan</span></div>
    <div className="page-heading">
      <div>
        <span className="eyebrow">KHÔNG GIAN QUẢN TRỊ</span>
        <h1>Tổng quan quản trị<span className="heading-dot">.</span></h1>
        <p>Chọn một chức năng quản lý để bắt đầu công việc.</p>
      </div>
    </div>
    <nav className="admin-overview-grid" aria-label="Các chức năng quản trị">
      {visibleSections.map(([path, title, description]) => <Link className="panel admin-overview-card" key={path} to={path}>
        <span className="admin-overview-card-title">{title}<Icon name="arrow" size={18} /></span>
        <span className="muted">{description}</span>
      </Link>)}
    </nav>
  </>;
}
