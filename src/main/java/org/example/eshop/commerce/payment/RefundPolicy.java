package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.order.OrderStore;
import org.springframework.stereotype.Component;
import java.math.*;
import java.util.*;

/** Pure eligibility over persisted finance/return snapshots; callers lock the order root for writes. */
@Component
public class RefundPolicy {
    private final OrderStore store;
    public RefundPolicy(OrderStore store){this.store=store;}
    static BigDecimal money(Object value){return new BigDecimal(value.toString());}
    static String amount(BigDecimal value){return value.setScale(2,RoundingMode.HALF_UP).toPlainString();}
    static boolean manual(Map<String,Object> payment){return "SUCCEEDED".equals(payment.get("status"))&&(PaymentQueryService.manual(payment)||SePaySettings.PROVIDER.equals(payment.get("provider")));}
    BigDecimal held(List<Map<String,Object>> refunds,Long payment,Long returned){
        return refunds.stream().filter(r->Set.of("PENDING","SUCCEEDED").contains(r.get("status")))
            .filter(r->payment==null||((Number)r.get("paymentId")).longValue()==payment)
            .filter(r->returned==null||r.get("returnRequestId")!=null&&((Number)r.get("returnRequestId")).longValue()==returned)
            .map(r->money(r.get("amount"))).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    List<Map<String,Object>> eligibleReturns(Map<String,Object> order,List<Map<String,Object>> refunds){
        long id=((Number)order.get("id")).longValue();BigDecimal subtotal=money(order.get("subtotal"));
        var candidates=store.rows("""
            SELECT rr.id,rr.return_number AS returnNumber,
              COALESCE(SUM(ri.received_quantity*oi.unit_price),0) AS receivedValue,
              COALESCE(SUM(CASE WHEN ri.received_quantity>oi.quantity THEN 1 ELSE 0 END),0) AS invalidLines
            FROM return_requests rr JOIN return_items ri ON ri.return_request_id=rr.id AND ri.order_id=rr.order_id
            JOIN order_items oi ON oi.id=ri.order_item_id AND oi.order_id=ri.order_id
            WHERE rr.order_id=? AND rr.status IN ('RECEIVED','COMPLETED') AND rr.resolution='REFUND'
              AND rr.approved_by IS NOT NULL
            GROUP BY rr.id,rr.return_number ORDER BY rr.id
            """,id);
        if(candidates.isEmpty()||subtotal.signum()<=0)return List.of();
        // Two received returns cannot legitimately claim the same original quantity twice.
        int invalid=store.jdbc().queryForObject("""
            SELECT COUNT(*) FROM (SELECT oi.id FROM return_items ri
              JOIN return_requests rr ON rr.id=ri.return_request_id AND rr.order_id=ri.order_id
              JOIN order_items oi ON oi.id=ri.order_item_id AND oi.order_id=ri.order_id
              WHERE rr.order_id=? AND rr.status IN ('RECEIVED','COMPLETED')
                AND rr.resolution='REFUND' AND rr.approved_by IS NOT NULL
              GROUP BY oi.id,oi.quantity HAVING SUM(ri.received_quantity)>oi.quantity) invalid_returns
            """,Integer.class,id);
        if(invalid>0)return List.of();
        BigDecimal collected=store.jdbc().queryForObject("SELECT COALESCE(SUM(amount),0) FROM payments WHERE order_id=? AND status='SUCCEEDED'",BigDecimal.class,id);
        var results=new ArrayList<Map<String,Object>>();
        for(var row:candidates){
            if(money(row.get("invalidLines")).signum()>0||money(row.get("receivedValue")).signum()<=0)continue;
            BigDecimal cap=money(row.get("receivedValue")).multiply(subtotal.subtract(money(order.get("discountAmount"))))
                .divide(subtotal,2,RoundingMode.DOWN).min(money(order.get("grandTotal"))).min(collected).max(BigDecimal.ZERO);
            long returned=((Number)row.get("id")).longValue();
            results.add(Map.of("id",returned,"returnNumber",row.get("returnNumber"),"refundableAmount",amount(cap),"availableRefundAmount",amount(cap.subtract(held(refunds,null,returned)).max(BigDecimal.ZERO))));
        }
        return results;
    }
    void validateReturn(Map<String,Object> order,List<Map<String,Object>> refunds,Long returned,BigDecimal requested){
        if(returned==null){if(!"CANCELLED".equals(order.get("status")))throw conflict("RETURN_NOT_REFUNDABLE");return;}
        var eligible=eligibleReturns(order,refunds).stream().filter(r->((Number)r.get("id")).longValue()==returned).findFirst().orElseThrow(()->conflict("RETURN_NOT_REFUNDABLE"));
        if(requested.compareTo(money(eligible.get("availableRefundAmount")))>0)throw conflict("RETURN_REFUND_EXCEEDS_BALANCE");
    }
    static CommerceException conflict(String code){return CommerceException.conflict(code,message(code));}
    static String message(String code){return switch(code){
        case "PAYMENT_NOT_REFUNDABLE"->"Khoản thu không thuộc đơn, chưa thu thành công hoặc không hỗ trợ hoàn thủ công.";
        case "REFUND_EXCEEDS_BALANCE"->"Số tiền vượt phần còn được hoàn, đã tính cả các yêu cầu đang chờ.";
        case "RETURN_NOT_REFUNDABLE"->"Cần đơn đã hủy hoặc yêu cầu hoàn cùng đơn đã được duyệt và nhận hàng thực tế.";
        case "RETURN_REFUND_EXCEEDS_BALANCE"->"Số tiền vượt giá trị hàng đã nhận lại sau giảm giá và các khoản hoàn đã giữ.";
        case "REFUND_RECONCILIATION_REQUIRED"->"Dữ liệu hoàn tiền cần đối soát trước khi tiếp tục.";
        case "REFUND_ALREADY_CONFIRMED"->"Yêu cầu đã được xác minh hoàn với chứng từ khác.";
        case "REFUND_ALREADY_RESOLVED"->"Yêu cầu đã được xử lý; không thể đổi kết quả hoặc ghi đè lý do.";
        case "REFUND_REFERENCE_IN_USE"->"Mã chứng từ đã gắn với khoản hoàn khác.";
        default->"Yêu cầu không còn ở trạng thái chờ hoặc không hỗ trợ xử lý thủ công.";
    };}
}
