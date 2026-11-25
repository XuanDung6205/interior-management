package org.example.eshop.commerce.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;

/** Transactional persistence helper: no async memory event and no private staff data in customer text. */
public final class OrderNotifications {
    private OrderNotifications(){}
    public static void placed(JdbcTemplate db,Clock clock,long order){enqueue(db,clock,order,"order:"+order+":placed","ORDER_PLACED","Đã nhận đơn hàng","Cửa hàng đã nhận đơn và đang chờ xác nhận.");}
    public static void fromAudit(JdbcTemplate db,Clock clock,long order,String key,String action,Map<String,Object> after){
        String title,body;
        switch(action){
            case "ORDER_STATUS_CHANGED"->{String status=Objects.toString(after.get("status"),"");title="Trạng thái đơn hàng đã cập nhật";body=switch(status){case "CONFIRMED"->"Cửa hàng đã xác nhận đơn hàng.";case "PROCESSING"->"Đơn hàng đang được chuẩn bị và giao nhận.";case "COMPLETED"->"Đơn hàng đã hoàn thành giao nhận, thanh toán và lắp đặt đã chọn.";case "CANCELLED"->"Đơn hàng đã được hủy. Khoản đã thu, nếu có, được theo dõi hoàn tiền riêng.";default->"Vui lòng xem chi tiết đơn hàng để theo dõi trạng thái mới.";};}
            case "ORDER_EXPIRED"->{title="Đơn COD đã hết thời hạn xác nhận";body="Đơn COD chưa xác nhận đã được hủy theo thời hạn. Hàng và lượt ưu đãi đang giữ đã được giải phóng.";}
            case "SEPAY_PAYMENT_CONFIRMED"->{title="Đã ghi nhận thanh toán SePay Sandbox";body="Giao dịch thử nghiệm đã được xác thực và ghi nhận cho đơn hàng.";}
            case "PAYMENT_CONFIRMED"->{title="Đã xác minh khoản thu";body="Cửa hàng đã đối chiếu chứng từ và ghi nhận khoản thu của đơn hàng.";}
            case "REFUND_REQUESTED"->{title="Đã tiếp nhận yêu cầu hoàn tiền";body="Yêu cầu hoàn đang chờ xử lý; đây chưa phải xác nhận tiền đã được trả lại.";}
            case "REFUND_CONFIRMED"->{title="Đã xác minh hoàn tiền";body="Cửa hàng đã đối chiếu chứng từ trả lại tiền. Xem lịch sử hoàn tiền trong đơn hàng.";}
            case "REFUND_CANCELLED"->{title="Yêu cầu hoàn tiền đã cập nhật";body="Một yêu cầu chưa gửi tiền đã được hủy để đối chiếu hoặc lập lại. Nghĩa vụ hoàn còn lại vẫn được theo dõi.";}
            case "RETURN_REQUESTED"->{title="Đã nhận đề nghị trả hàng";body="Đề nghị trả hàng đang chờ cửa hàng kiểm tra.";}
            case "RETURN_APPROVED"->{title="Đề nghị trả hàng được duyệt";body="Cửa hàng đã duyệt đề nghị. Khoản hoàn chỉ được xử lý sau khi có bằng chứng nhận lại hàng hợp lệ.";}
            case "RETURN_REJECTED"->{title="Đề nghị trả hàng đã được phản hồi";body="Cửa hàng đã phản hồi đề nghị trả hàng. Vui lòng liên hệ cửa hàng để được hỗ trợ.";}
            case "RETURN_RECEIVED"->{title="Cửa hàng đã nhận lại hàng";body="Số lượng hàng trả đã được ghi nhận. Yêu cầu hoàn tiền được theo dõi riêng.";}
            case "INSTALLATION_COMPLETED"->{title="Đã xác nhận lắp đặt";body="Nhân viên đã xác nhận hoàn tất lắp đặt của đơn hàng.";}
            case "SHIPMENT_DELIVERED"->{title="Đã ghi nhận giao hàng";body="Một chuyến giao của đơn hàng đã được xác nhận giao thành công.";}
            default->{return;}
        }
        enqueue(db,clock,order,key,action,title,body);
    }
    private static void enqueue(JdbcTemplate db,Clock clock,long order,String key,String type,String title,String body){
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Notification enqueue requires business transaction");
        var now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        db.update("""
            INSERT INTO commerce_notification_outbox(event_key,order_id,recipient_user_id,notification_type,title,body,state,attempts,next_attempt_at,created_at,updated_at)
            SELECT ?,id,user_id,?,?,?,'PENDING',0,?,?,? FROM orders WHERE id=? AND user_id IS NOT NULL
            """,key,type,title,body,now,now,now,order);
    }
}
