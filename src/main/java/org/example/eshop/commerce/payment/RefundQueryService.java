package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.order.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service
@Transactional(readOnly=true)
public class RefundQueryService {
    private final OrderStore store;private final OrderAdminAccess access;private final RefundPolicy policy;private final SePayRefundSettings sepay;
    public RefundQueryService(OrderStore store,OrderAdminAccess access,RefundPolicy policy,SePayRefundSettings sepay){this.store=store;this.access=access;this.policy=policy;this.sepay=sepay;}
    private static final String COLUMNS="r.id,r.order_id AS orderId,r.payment_id AS paymentId,r.return_request_id AS returnRequestId,r.provider,r.status,r.amount,r.currency,r.reason,r.provider_refund_id AS reference,r.confirmation_note AS confirmationNote,r.resolution_note AS resolutionNote,r.processed_by AS processorId,r.completed_at AS completedAt,r.created_at AS createdAt,r.updated_at AS updatedAt,r.sepay_execution_state AS sepayExecutionState,r.sepay_request_key AS sepayRequestKey,r.sepay_refund_id AS sepayRefundId";
    long orderId(long refund){var found=store.jdbc().query("SELECT order_id FROM refunds WHERE id=?",(rs,n)->rs.getLong(1),refund);if(found.isEmpty())throw CommerceException.notFound("Không tìm thấy yêu cầu hoàn tiền.");return found.get(0);}
    List<Map<String,Object>> payments(long id,boolean lock){return store.rows("SELECT id,method,provider,status,amount,currency,sepay_transaction_id AS sepayTransactionId,sepay_bank_account_id AS sepayBankAccountId FROM payments WHERE order_id=? ORDER BY id"+(lock?" FOR UPDATE":""),id);}
    List<Map<String,Object>> refunds(long id,boolean lock){return store.rows("SELECT "+COLUMNS+(lock?"":",u.full_name AS processorName")+" FROM refunds r"+(lock?"":" LEFT JOIN users u ON u.id=r.processed_by")+" WHERE r.order_id=? ORDER BY r.id"+(lock?" FOR UPDATE":""),id);}
    public Map<String,Object> order(long id){access.requireStaff();return summary(store.find(id,false),null);}
    public Map<String,Object> detail(long id){access.requireStaff();return summary(store.find(orderId(id),false),id);}
    Map<String,Object> summary(Map<String,Object> order,Long selected){
        long id=((Number)order.get("id")).longValue();var payments=payments(id,false);var refunds=refunds(id,false);var returns=policy.eligibleReturns(order,refunds);
        BigDecimal collected=payments.stream().filter(p->"SUCCEEDED".equals(p.get("status"))).map(p->RefundPolicy.money(p.get("amount"))).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal pending=refunds.stream().filter(r->"PENDING".equals(r.get("status"))).map(r->RefundPolicy.money(r.get("amount"))).reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal refunded=refunds.stream().filter(r->"SUCCEEDED".equals(r.get("status"))).map(r->RefundPolicy.money(r.get("amount"))).reduce(BigDecimal.ZERO,BigDecimal::add);
        var budgets=new ArrayList<Map<String,Object>>();var byPayment=new HashMap<Long,Map<String,Object>>();boolean availableManual=false;
        for(var p:payments){
            long payment=((Number)p.get("id")).longValue();byPayment.put(payment,p);var budget=new LinkedHashMap<>(p);budget.remove("sepayTransactionId");budget.remove("sepayBankAccountId");
            BigDecimal held=policy.held(refunds,payment,null),done=refunds.stream().filter(r->"SUCCEEDED".equals(r.get("status"))&&((Number)r.get("paymentId")).longValue()==payment).map(r->RefundPolicy.money(r.get("amount"))).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal available=RefundPolicy.manual(p)?RefundPolicy.money(p.get("amount")).subtract(held).max(BigDecimal.ZERO):BigDecimal.ZERO;
            budget.put("pendingRefundAmount",RefundPolicy.amount(held.subtract(done)));budget.put("refundedAmount",RefundPolicy.amount(done));budget.put("availableRefundAmount",RefundPolicy.amount(available));budget.put("canRefund",RefundPolicy.manual(p)&&available.signum()>0);availableManual|=Boolean.TRUE.equals(budget.get("canRefund"));budgets.add(budget);
        }
        String block=!availableManual?"REFUND_EXCEEDS_BALANCE":!"CANCELLED".equals(order.get("status"))&&returns.stream().noneMatch(r->RefundPolicy.money(r.get("availableRefundAmount")).signum()>0)?"RETURN_NOT_REFUNDABLE":null;
        var result=new LinkedHashMap<String,Object>();result.put("orderId",id);result.put("orderNumber",order.get("orderNumber"));result.put("orderStatus",order.get("status"));result.put("currency",order.get("currency"));result.put("collectedAmount",RefundPolicy.amount(collected));result.put("pendingRefundAmount",RefundPolicy.amount(pending));result.put("refundedAmount",RefundPolicy.amount(refunded));result.put("availableRefundAmount",RefundPolicy.amount(collected.subtract(pending).subtract(refunded).max(BigDecimal.ZERO)));result.put("canCreate",block==null);result.put("createBlockReason",block);result.put("payments",budgets);result.put("returns",returns);result.put("items",refunds.stream().map(r->view(r,byPayment.get(((Number)r.get("paymentId")).longValue()))).toList());if(selected!=null)result.put("selectedRefundId",selected);return result;
    }
    private Map<String,Object> view(Map<String,Object> r,Map<String,Object> payment){
        var result=new LinkedHashMap<>(r);result.remove("processorId");result.remove("processorName");var processor=new LinkedHashMap<String,Object>();processor.put("id",r.get("processorId"));processor.put("fullName",r.get("processorName"));result.put("processedBy",r.get("processorId")==null?null:processor);
        for(String field:List.of("sepayExecutionState","sepayRequestKey","sepayRefundId"))result.remove(field);if(SePaySettings.PROVIDER.equals(r.get("provider")))result.put("sepayExecution",sepay.view(r,payment));
        boolean allowed=!SePayRefundSettings.claimed(r)&&"PENDING".equals(r.get("status"))&&payment!=null&&RefundPolicy.manual(payment)&&Objects.equals(r.get("provider"),payment.get("provider"));result.put("canConfirm",allowed);result.put("canCancel",allowed);result.put("actionBlockReason",allowed?null:SePayRefundSettings.claimed(r)?"SEPAY_REFUND_EXECUTION_CLAIMED":"REFUND_NOT_PENDING");return result;
    }
    public Map<String,Object> list(int page,int size,String status,String query){
        access.requireStaff();status=status.trim();query=query.trim();if(page<0||size<1||size>100||query.length()>150||!status.isEmpty()&&!Set.of("PENDING","SUCCEEDED","FAILED","CANCELLED").contains(status))throw CommerceException.badRequest("INVALID_FILTER","Bộ lọc hoặc phân trang không hợp lệ.");
        var args=new ArrayList<Object>();String where=" WHERE 1=1";if(!status.isEmpty()){where+=" AND r.status=?";args.add(status);}if(!query.isEmpty()){where+=" AND LOCATE(?,CONCAT_WS(' ',o.order_number,u.username,u.full_name,r.provider_refund_id))>0";args.add(query);}
        String from=" FROM refunds r JOIN orders o ON o.id=r.order_id LEFT JOIN users u ON u.id=o.user_id";long count=store.jdbc().queryForObject("SELECT COUNT(*)"+from+where,Long.class,args.toArray());args.add(size);args.add((long)page*size);
        var rows=store.rows("SELECT "+COLUMNS+",v.full_name AS processorName,o.order_number AS orderNumber,o.status AS orderStatus,u.id AS customerId,u.username,u.full_name AS fullName,p.method AS paymentMethod,p.provider AS paymentProvider,p.status AS paymentStatus,p.sepay_transaction_id AS sepayTransactionId,p.sepay_bank_account_id AS sepayBankAccountId"+from+" JOIN payments p ON p.id=r.payment_id LEFT JOIN users v ON v.id=r.processed_by"+where+" ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",args.toArray());
        var items=new ArrayList<Map<String,Object>>();for(var row:rows){var p=new LinkedHashMap<String,Object>();p.put("method",row.get("paymentMethod"));p.put("provider",row.get("paymentProvider"));p.put("status",row.get("paymentStatus"));p.put("sepayTransactionId",row.get("sepayTransactionId"));p.put("sepayBankAccountId",row.get("sepayBankAccountId"));var r=new LinkedHashMap<>(row);for(String field:List.of("paymentMethod","paymentProvider","paymentStatus","orderNumber","orderStatus","customerId","username","fullName","sepayTransactionId","sepayBankAccountId"))r.remove(field);var item=view(r,p);item.put("orderNumber",row.get("orderNumber"));item.put("orderStatus",row.get("orderStatus"));var customer=new LinkedHashMap<String,Object>();customer.put("id",row.get("customerId"));customer.put("username",row.get("username"));customer.put("fullName",row.get("fullName"));item.put("customer",customer);items.add(item);}
        return Map.of("items",items,"page",page,"size",size,"totalElements",count,"totalPages",(count+size-1)/size);
    }
}
