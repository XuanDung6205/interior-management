package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.order.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class PaymentQueryService {
    private final OrderStore store;
    private final CheckoutQuoteService actors;
    private final OrderAdminAccess access;
    private final BankTransferSettings bank;
    private final Clock clock;
    private final SePaySettings sepaySettings;private final SePayPayments sepay;
    public PaymentQueryService(OrderStore store,CheckoutQuoteService actors,OrderAdminAccess access,BankTransferSettings bank,Clock clock,SePaySettings sepaySettings,SePayPayments sepay){this.sepaySettings=sepaySettings;this.sepay=sepay;this.store=store;this.actors=actors;this.access=access;this.bank=bank;this.clock=clock;}
    public Map<String,Object> customer(long id){return summary(store.owned(id,actors.actorId(),false),false,null);}
    public Map<String,Object> staff(long payment){access.requireStaff();return summary(store.find(orderId(payment),false),true,payment);}
    long orderId(long payment){var ids=store.jdbc().query("SELECT order_id FROM payments WHERE id=?",(rs,n)->rs.getLong(1),payment);if(ids.isEmpty())throw CommerceException.notFound("Không tìm thấy khoản thanh toán.");return ids.get(0);}
    record Totals(BigDecimal collected,BigDecimal pending,BigDecimal refunded){BigDecimal held(){return pending.add(refunded);}}
    Totals totals(long id){
        BigDecimal collected=store.jdbc().queryForObject("SELECT COALESCE(SUM(amount),0) FROM payments WHERE order_id=? AND status='SUCCEEDED'",BigDecimal.class,id);
        var refunds=store.jdbc().queryForMap("SELECT COALESCE(SUM(CASE WHEN status='PENDING' THEN amount ELSE 0 END),0) AS pending,COALESCE(SUM(CASE WHEN status='SUCCEEDED' THEN amount ELSE 0 END),0) AS refunded FROM refunds WHERE order_id=?",id);
        return new Totals(collected,(BigDecimal)refunds.get("pending"),(BigDecimal)refunds.get("refunded"));
    }
    String createBlock(Map<String,Object> order,Totals t,List<Map<String,Object>> payments){
        if(Set.of("CANCELLED","COMPLETED").contains(order.get("status")))return "ORDER_NOT_PAYABLE";
        BigDecimal total=decimal(order.get("grandTotal"));
        if(total.signum()==0)return "PAYMENT_NOT_REQUIRED";
        if(t.held().signum()>0)return "REFUND_RECONCILIATION_REQUIRED";
        if(t.collected().compareTo(total)>=0)return "ORDER_ALREADY_PAID";
        if(payments.stream().anyMatch(p->"PENDING".equals(p.get("status"))))return "PAYMENT_PENDING_EXISTS";
        return null;
    }
    String confirmationBlock(Map<String,Object> order,Totals t,Map<String,Object> p){
        boolean late="CANCELLED".equals(order.get("status"));
        if(!manual(p)||!(late?Set.of("PENDING","CANCELLED","EXPIRED"):Set.of("PENDING")).contains(p.get("status")))return "PAYMENT_NOT_CONFIRMABLE";
        if(!late&&expired(p))return "PAYMENT_EXPIRED";
        if(!late&&t.held().signum()>0)return "REFUND_RECONCILIATION_REQUIRED";
        if(decimal(p.get("amount")).signum()<=0||decimal(p.get("amount")).compareTo(decimal(order.get("grandTotal")).subtract(t.collected()))>0)return "PAYMENT_EXCEEDS_BALANCE";
        if("COMPLETED".equals(order.get("status")))return "ORDER_NOT_PAYABLE";
        return null;
    }
    boolean expired(Map<String,Object> p){return p.get("expiresAt")!=null&&!Instant.parse(p.get("expiresAt").toString()).isAfter(clock.instant());}
    static boolean manual(Map<String,Object> p){return ("COD".equals(p.get("method"))&&"COD".equals(p.get("provider")))||("BANK_TRANSFER".equals(p.get("method"))&&"MANUAL_BANK".equals(p.get("provider")));}
    private static final String PAYMENT_COLUMNS="p.sepay_code AS sepayCode,p.sepay_bank AS sepayBank,p.sepay_account_number AS sepayAccountNumber,p.sepay_account_holder AS sepayAccountHolder,p.sepay_content AS sepayContent,p.id,p.order_id AS orderId,p.method,p.provider,p.status,p.amount,p.currency,p.provider_transaction_id AS reference,p.confirmation_note AS confirmationNote,p.verified_by AS verifierId,p.paid_at AS paidAt,p.expires_at AS expiresAt,p.created_at AS createdAt";
    List<Map<String,Object>> payments(long id,boolean lock){
        // Lock only payment rows. Joining the verifier here would acquire extra user locks out of order.
        return store.rows("SELECT "+PAYMENT_COLUMNS+(lock?"":",v.full_name AS verifierName")+" FROM payments p"+(lock?"":" LEFT JOIN users v ON v.id=p.verified_by")+" WHERE p.order_id=? ORDER BY p.id"+(lock?" FOR UPDATE":""),id);
    }
    Map<String,Object> summary(Map<String,Object> order,boolean staff,Long selected){
        long id=((Number)order.get("id")).longValue();var t=totals(id);var payments=payments(id,false);String blocked=createBlock(order,t,payments);var result=new LinkedHashMap<String,Object>();
        result.put("sepay",sepaySettings.publicView());result.put("orderId",id);result.put("orderNumber",order.get("orderNumber"));result.put("orderStatus",order.get("status"));result.put("currency",order.get("currency"));result.put("totalAmount",order.get("grandTotal"));result.put("collectedAmount",amount(t.collected()));result.put("pendingRefundAmount",amount(t.pending()));result.put("refundedAmount",amount(t.refunded()));result.put("netCollectedAmount",amount(t.collected().subtract(t.held()).max(BigDecimal.ZERO)));result.put("remainingAmount",amount("CANCELLED".equals(order.get("status"))?BigDecimal.ZERO:decimal(order.get("grandTotal")).subtract(t.collected()).max(BigDecimal.ZERO)));result.put("canCreate",blocked==null);result.put("createBlockReason",blocked);result.put("items",payments.stream().map(p->view(p,order,t,staff)).toList());result.put("bankTransfer",bank.forOrder(order.get("orderNumber").toString()));if(selected!=null)result.put("selectedPaymentId",selected);
        if(staff)result.put("sepayEvents",store.rows("SELECT e.id,e.provider_event_id AS providerEventId,e.payment_id AS paymentId,e.processing_status AS status,e.error_message AS reason,e.received_at AS receivedAt,JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.reference')) AS reference FROM payment_events e JOIN payments p ON p.id=e.payment_id WHERE p.order_id=? AND e.provider=? ORDER BY e.id DESC LIMIT 25",id,SePaySettings.PROVIDER));return result;
    }
    private Map<String,Object> view(Map<String,Object> p,Map<String,Object> order,Totals t,boolean staff){
        var result=new LinkedHashMap<>(p);
        if(SePaySettings.PROVIDER.equals(p.get("provider")))result.put("sepay",sepay.view(p,"PENDING".equals(p.get("status"))&&!Set.of("CANCELLED","COMPLETED").contains(order.get("status"))&&!expired(p)&&t.collected().compareTo(decimal(order.get("grandTotal")))<0&&t.held().signum()==0,staff));
        for(String field:List.of("sepayCode","sepayBank","sepayAccountNumber","sepayAccountHolder","sepayContent"))result.remove(field);
        result.remove("verifierId");result.remove("verifierName");result.put("isExpired",expired(p));
        if(!staff)result.remove("confirmationNote");
        else {Object verifier=p.get("verifierId");var verifiedBy=new LinkedHashMap<String,Object>();verifiedBy.put("id",verifier);verifiedBy.put("fullName",p.get("verifierName"));result.put("verifiedBy",verifier==null?null:verifiedBy);String blocked=confirmationBlock(order,t,p);result.put("canConfirm",blocked==null);result.put("confirmationMessage",blocked==null?null:message(blocked));result.put("isLateCollection","CANCELLED".equals(order.get("status")));}
        return result;
    }
    public Map<String,Object> list(int page,int size,String status,String method,String query){
        access.requireStaff();status=status.trim();method=method.trim();query=query.trim();
        if(page<0||size<1||size>100||query.length()>150||(!status.isEmpty()&&!Set.of("PENDING","SUCCEEDED","FAILED","CANCELLED","EXPIRED").contains(status))||(!method.isEmpty()&&!Set.of("COD","BANK_TRANSFER","GATEWAY").contains(method)))throw CommerceException.badRequest("INVALID_FILTER","Bộ lọc hoặc phân trang không hợp lệ.");
        var args=new ArrayList<Object>();String where=" WHERE 1=1";if(!status.isEmpty()){where+=" AND p.status=?";args.add(status);}if(!method.isEmpty()){where+=" AND p.method=?";args.add(method);}if(!query.isEmpty()){where+=" AND LOCATE(?,CONCAT_WS(' ',o.order_number,u.username,u.full_name,p.provider_transaction_id))>0";args.add(query);}
        String from=" FROM payments p JOIN orders o ON o.id=p.order_id LEFT JOIN users u ON u.id=o.user_id";
        long count=store.jdbc().queryForObject("SELECT COUNT(*)"+from+where,Long.class,args.toArray());args.add(size);args.add((long)page*size);
        var selected=store.rows("SELECT "+PAYMENT_COLUMNS+",v.full_name AS verifierName,o.grand_total AS grandTotal,o.order_number AS orderNumber,o.status AS orderStatus,u.id AS customerId,u.username,u.full_name AS fullName"+from+" LEFT JOIN users v ON v.id=p.verified_by"+where+" ORDER BY p.created_at DESC,p.id DESC LIMIT ? OFFSET ?",args.toArray());
        // Four bounded queries for a page: count, rows, gross collections and refunds.
        // Aggregate only selected orders via existing order/status indexes; no per-row reads.
        var ids=selected.stream().map(row->((Number)row.get("orderId")).longValue()).distinct().toList();
        var collected=new HashMap<Long,BigDecimal>();var refunds=new HashMap<Long,Totals>();
        if(!ids.isEmpty()){
            String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
            store.jdbc().query("SELECT order_id,SUM(amount) FROM payments WHERE status='SUCCEEDED' AND order_id IN ("+placeholders+") GROUP BY order_id",rs->{collected.put(rs.getLong(1),rs.getBigDecimal(2));},ids.toArray());
            store.jdbc().query("SELECT order_id,SUM(CASE WHEN status='PENDING' THEN amount ELSE 0 END),SUM(CASE WHEN status='SUCCEEDED' THEN amount ELSE 0 END) FROM refunds WHERE order_id IN ("+placeholders+") GROUP BY order_id",rs->{refunds.put(rs.getLong(1),new Totals(BigDecimal.ZERO,rs.getBigDecimal(2),rs.getBigDecimal(3)));},ids.toArray());
        }
        var items=new ArrayList<Map<String,Object>>();
        for(var row:selected){
            long id=((Number)row.get("orderId")).longValue();var refund=refunds.getOrDefault(id,new Totals(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));var t=new Totals(collected.getOrDefault(id,BigDecimal.ZERO),refund.pending(),refund.refunded());
            var order=Map.<String,Object>of("status",row.get("orderStatus"),"grandTotal",row.get("grandTotal"));
            var p=new LinkedHashMap<>(row);for(String field:List.of("grandTotal","orderNumber","orderStatus","customerId","username","fullName"))p.remove(field);
            var item=view(p,order,t,true);item.put("orderNumber",row.get("orderNumber"));item.put("orderStatus",row.get("orderStatus"));var customer=new LinkedHashMap<String,Object>();customer.put("id",row.get("customerId"));customer.put("username",row.get("username"));customer.put("fullName",row.get("fullName"));item.put("customer",customer);items.add(item);
        }
        return Map.of("items",items,"page",page,"size",size,"totalElements",count,"totalPages",(count+size-1)/size);
    }
    static BigDecimal decimal(Object value){return new BigDecimal(value.toString());}
    static String amount(BigDecimal value){return value.setScale(2).toPlainString();}
    static String message(String code){return switch(code){case "PAYMENT_PENDING_EXISTS"->"Đơn đã có khoản thanh toán đang chờ xử lý.";case "PAYMENT_EXPIRED"->"Khoản thanh toán đã hết hạn.";case "PAYMENT_EXCEEDS_BALANCE"->"Khoản thu vượt số tiền còn phải thu. Cần đối soát thủ công.";case "REFUND_RECONCILIATION_REQUIRED"->"Đơn có yêu cầu hoàn tiền; cần đối soát trước khi ghi nhận thêm khoản thu.";case "ORDER_ALREADY_PAID"->"Đơn đã thu đủ tiền.";case "PAYMENT_NOT_REQUIRED"->"Đơn không cần thu tiền.";case "ORDER_NOT_PAYABLE"->"Trạng thái đơn không cho tạo khoản thanh toán mới.";case "PAYMENT_BANK_NOT_CONFIGURED"->"Chưa cấu hình hướng dẫn chuyển khoản.";default->"Khoản thanh toán không hỗ trợ xác nhận thủ công ở trạng thái hiện tại.";};}
}
