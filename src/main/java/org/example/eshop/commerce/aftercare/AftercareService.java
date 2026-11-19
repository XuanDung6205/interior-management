package org.example.eshop.commerce.aftercare;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.order.*;
import org.example.eshop.commerce.port.OrderAuditPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static org.example.eshop.commerce.aftercare.AftercareController.*;

/** Real delivered goods, staff evidence, and explicit restock quantities share the financial order lock. */
@Service
public class AftercareService {
    private final OrderStore store;
    private final OrderAdminAccess access;
    private final CheckoutQuoteService actors;
    private final OrderAuditPort audit;
    private final Clock clock;
    private final OrderCompletionPolicy completion;
    private final OrderLifecycleService lifecycle;

    public AftercareService(OrderStore store,OrderAdminAccess access,CheckoutQuoteService actors,
            OrderAuditPort audit,Clock clock,OrderCompletionPolicy completion,OrderLifecycleService lifecycle){
        this.store=store;this.access=access;this.actors=actors;this.audit=audit;this.clock=clock;
        this.completion=completion;this.lifecycle=lifecycle;
    }

    @Transactional(readOnly=true)
    public Map<String,Object> customer(long id){store.owned(id,actors.actorId(),false);return summary(id,false);}

    @Transactional(readOnly=true)
    public Map<String,Object> staff(long id){access.requireStaff();return summary(id,true);}

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> request(long id,ReturnRequest body,String key){
        long actor=actors.actorId();
        // Check ownership before acquiring staff-style referenced-user/root locks or revealing replay data.
        store.owned(id,actor,false);
        String reason=required(body==null?null:body.reason(),"reason");
        var intent=requestedItems(body==null?null:body.items());
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,64}"))throw invalid("idempotencyKey","Idempotency-Key cần 8–64 ký tự chữ, số, _ hoặc -.");
        var order=access.lock(id,actor);store.owned(id,actor,false);
        String number="RT-"+actors.hash(List.of(actor,key)).substring(0,32);
        var prior=store.rows("SELECT id,order_id AS orderId,reason FROM return_requests WHERE return_number=?",number);
        if(!prior.isEmpty()){
            var row=prior.get(0);long previous=number(row,"id");
            var previousItems=store.rows("SELECT order_item_id AS orderItemId,quantity FROM return_items WHERE return_request_id=? ORDER BY order_item_id",previous);
            var previousIntent=previousItems.stream().map(line->List.of(number(line,"orderItemId"),(long)integer(line,"quantity"))).toList();
            var currentIntent=intent.stream().map(line->List.of(line.orderItemId(),line.quantity().longValue())).toList();
            if(number(row,"orderId")!=id||!reason.equals(row.get("reason"))||!previousIntent.equals(currentIntent))throw conflict("IDEMPOTENCY_CONFLICT");
            return selected(id,previous,false);
        }
        if(!Set.of("PROCESSING","COMPLETED").contains(order.get("status")))throw conflict("RETURN_NOT_READY");
        var lines=items(id);var byId=new HashMap<Long,Map<String,Object>>();lines.forEach(line->byId.put(number(line,"id"),line));
        for(var line:intent){
            var original=byId.get(line.orderItemId());
            if(original==null)throw invalid("items","Sản phẩm không thuộc đơn hàng.");
            if(line.quantity()>integer(original,"returnableQuantity"))throw conflict("RETURN_QUANTITY_EXCEEDED");
        }
        var now=now();
        long returned=store.insert("INSERT INTO return_requests(return_number,order_id,requested_by,status,reason,resolution,requested_at,updated_at) VALUES(?,?,?,'REQUESTED',?,'REFUND',?,?)",number,id,actor,reason,now,now);
        for(var line:intent)store.jdbc().update("INSERT INTO return_items(return_request_id,order_id,order_item_id,quantity,reason) VALUES(?,?,?,?,?)",returned,id,line.orderItemId(),line.quantity(),reason);
        audit.append(actor,id,"RETURN_REQUESTED",Map.of(),Map.of("returnId",returned,"returnNumber",number,"status","REQUESTED"));
        return selected(id,returned,false);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> decide(long id,Decision body){
        long actor=access.requireStaff();String reason=required(body==null?null:body.reason(),"reason");
        if(body==null||body.decision()==null||!Set.of("APPROVE","REJECT").contains(body.decision()))throw invalid("decision","Chọn APPROVE hoặc REJECT.");
        long orderId=returnOrder(id);access.lock(orderId,actor);var returned=lockedReturn(id,orderId);
        String fingerprint=actors.hash(List.of(body.decision(),reason));
        var prior=proof(orderId,"RETURN_APPROVED','RETURN_REJECTED","returnId",id);
        if(prior!=null){if(!fingerprint.equals(prior))throw conflict("RETURN_STATE_CHANGED");return summary(orderId,true);}
        if(!"REQUESTED".equals(returned.get("status")))throw conflict("RETURN_STATE_CHANGED");
        String status="APPROVE".equals(body.decision())?"APPROVED":"REJECTED";
        store.jdbc().update("UPDATE return_requests SET status=?,staff_note=?,approved_by=?,resolved_at=?,updated_at=? WHERE id=?",status,reason,"APPROVED".equals(status)?actor:null,"REJECTED".equals(status)?now():null,now(),id);
        audit.append(actor,orderId,"APPROVED".equals(status)?"RETURN_APPROVED":"RETURN_REJECTED",Map.of("returnId",id,"status","REQUESTED"),Map.of("returnId",id,"status",status,"fingerprint",fingerprint,"staffNote",reason));
        return summary(orderId,true);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> receive(long id,Receipt body){
        long actor=access.requireStaff();attest(body==null?null:body.acknowledged());
        if(body.warehouseId()==null||body.warehouseId()<=0)throw invalid("warehouseId","Chọn kho nhận hàng.");
        var intent=receivedItems(body.items());
        String fingerprint=actors.hash(List.of(body.warehouseId(),intent.stream().map(line->List.of(line.returnItemId(),line.receivedQuantity(),line.restockQuantity(),line.conditionNote())).toList()));
        long orderId=returnOrder(id);var order=access.lock(orderId,actor);var returned=lockedReturn(id,orderId);
        var lines=store.rows("SELECT ri.id,ri.order_item_id AS orderItemId,ri.quantity,oi.variant_id AS variantId FROM return_items ri JOIN order_items oi ON oi.id=ri.order_item_id AND oi.order_id=ri.order_id WHERE ri.return_request_id=? ORDER BY ri.id FOR UPDATE",id);
        String previous=proof(orderId,"RETURN_RECEIVED","returnId",id);
        if(previous!=null){if(!fingerprint.equals(previous))throw conflict("RETURN_RECEIPT_CONFLICT");return summary(orderId,true);}
        if(!"APPROVED".equals(returned.get("status"))||returned.get("approvedBy")==null||!Set.of("PROCESSING","COMPLETED").contains(order.get("status")))throw conflict("RETURN_STATE_CHANGED");
        if(lines.size()!=intent.size())throw invalid("items","Cần ghi nhận đúng tất cả các dòng hàng yêu cầu trả.");
        var received=new HashMap<Long,ReceivedItem>();intent.forEach(line->received.put(line.returnItemId(),line));
        var originals=new HashMap<Long,Map<String,Object>>();items(orderId).forEach(line->originals.put(number(line,"id"),line));
        var restock=new TreeMap<Long,Long>();
        for(var line:lines){
            var receipt=received.get(number(line,"id"));
            if(receipt==null||receipt.receivedQuantity()>integer(line,"quantity"))throw invalid("items","Số lượng nhận không được vượt yêu cầu trả.");
            var original=originals.get(number(line,"orderItemId"));
            if(original==null||integer(original,"claimedQuantity")>Math.min(integer(original,"quantity"),integer(original,"deliveredQuantity")))throw conflict("RETURN_QUANTITY_EXCEEDED");
            if(receipt.restockQuantity()>0){
                if(line.get("variantId")==null)throw conflict("RETURN_STOCK_UNAVAILABLE");
                restock.merge(number(line,"variantId"),receipt.restockQuantity().longValue(),Long::sum);
            }
        }
        // All warehouses precede balance locks, matching dispatch and existing stock writers.
        var warehouses=store.jdbc().query("SELECT id,is_active FROM warehouses ORDER BY id LOCK IN SHARE MODE",(rs,n)->Map.entry(rs.getLong(1),rs.getBoolean(2)));
        if(warehouses.stream().noneMatch(w->w.getKey().equals(body.warehouseId())&&w.getValue()))throw conflict("WAREHOUSE_INACTIVE");
        for(var entry:restock.entrySet()){
            long variant=entry.getKey(),warehouse=body.warehouseId();
            store.jdbc().update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) VALUES(?,?,0) ON DUPLICATE KEY UPDATE warehouse_id=VALUES(warehouse_id)",warehouse,variant);
            var balance=store.rows("SELECT on_hand_quantity AS onHand,version FROM inventory_balances WHERE warehouse_id=? AND variant_id=? FOR UPDATE",warehouse,variant).get(0);
            if(number(balance,"onHand")+entry.getValue()>Integer.MAX_VALUE)throw conflict("STOCK_LIMIT");
            int changed=store.jdbc().update("UPDATE inventory_balances SET on_hand_quantity=on_hand_quantity+?,version=version+1 WHERE warehouse_id=? AND variant_id=? AND version=?",entry.getValue(),warehouse,variant,number(balance,"version"));
            if(changed!=1)throw conflict("STOCK_CHANGED");
        }
        var now=now();
        for(var line:lines){
            long lineId=number(line,"id");var receipt=received.get(lineId);
            store.jdbc().update("UPDATE return_items SET received_quantity=?,restocked_quantity=?,condition_note=? WHERE id=?",receipt.receivedQuantity(),receipt.restockQuantity(),receipt.conditionNote(),lineId);
            if(receipt.restockQuantity()>0)store.jdbc().update("INSERT INTO inventory_movements(warehouse_id,variant_id,order_item_id,movement_type,quantity_delta,reference_code,idempotency_key,reason,performed_by,created_at) VALUES(?,?,?,'RETURN',?,?,?,?,?,?)",body.warehouseId(),number(line,"variantId"),number(line,"orderItemId"),receipt.restockQuantity(),returned.get("returnNumber"),"return-item:"+lineId,"Nhận lại hàng trả "+returned.get("returnNumber"),actor,now);
        }
        store.jdbc().update("UPDATE return_requests SET status='RECEIVED',resolved_at=?,updated_at=? WHERE id=?",now,now,id);
        audit.append(actor,orderId,"RETURN_RECEIVED",Map.of("returnId",id,"status","APPROVED"),Map.of("returnId",id,"status","RECEIVED","fingerprint",fingerprint,"warehouseId",body.warehouseId()));
        return summary(orderId,true);
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> install(long id,Installation body){
        long actor=access.requireStaff();attest(body==null?null:body.acknowledged());
        String reference=clean(body.reference()),note=clean(body.note());
        if(!reference.matches("[A-Za-z0-9._:/-]{6,150}"))throw invalid("reference","Mã chứng từ gồm 6–150 ký tự chữ, số, . _ : / -.");
        if(note.length()>1000)throw invalid("note","Ghi chú tối đa 1000 ký tự.");
        var hint=store.jdbc().queryForList("SELECT order_id FROM shipments WHERE id=?",Long.class,id);
        if(hint.isEmpty())throw CommerceException.notFound("Không tìm thấy vận đơn.");
        long orderId=hint.get(0);var order=access.lock(orderId,actor);
        // Completion may lock the coupon; take it before shipment children to retain the lifecycle ordering.
        store.jdbc().queryForList("SELECT c.id FROM coupons c JOIN orders o ON o.coupon_id=c.id WHERE o.id=? FOR UPDATE",Long.class,orderId);
        var shipments=store.rows("SELECT id,status,installation_required AS installationRequired,installation_status AS installationStatus,delivered_at AS deliveredAt FROM shipments WHERE id=? AND order_id=? FOR UPDATE",id,orderId);
        if(shipments.isEmpty())throw conflict("INSTALLATION_NOT_READY");var shipment=shipments.get(0);
        String fingerprint=actors.hash(List.of(reference,note));String previous=proof(orderId,"INSTALLATION_COMPLETED","shipmentId",id);
        if(previous!=null){if(!fingerprint.equals(previous))throw conflict("INSTALLATION_ALREADY_COMPLETED");return summary(orderId,true);}
        if("COMPLETED".equals(shipment.get("installationStatus")))throw conflict("INSTALLATION_ALREADY_COMPLETED");
        if(!installationReady(order,shipment))throw conflict("INSTALLATION_NOT_READY");
        var now=now();store.jdbc().update("UPDATE shipments SET installation_status='COMPLETED',installation_completed_at=?,installation_staff_id=?,updated_at=? WHERE id=?",now,actor,now,id);
        audit.append(actor,orderId,"INSTALLATION_COMPLETED",Map.of("shipmentId",id,"installationStatus",shipment.get("installationStatus")),Map.of("shipmentId",id,"installationStatus","COMPLETED","reference",reference,"note",note,"fingerprint",fingerprint));
        if("PROCESSING".equals(order.get("status"))&&completion.evaluate(orderId).eligible())lifecycle.transition(orderId,"PROCESSING","COMPLETED","Đã giao hàng, thu tiền và hoàn tất lắp đặt theo yêu cầu.");
        return summary(orderId,true);
    }

    private Map<String,Object> selected(long order,long returned,boolean staff){var result=summary(order,staff);result.put("selectedReturnId",returned);return result;}

    private Map<String,Object> summary(long id,boolean staff){
        var order=store.find(id,false);var result=new LinkedHashMap<String,Object>();
        result.put("orderId",id);result.put("orderNumber",order.get("orderNumber"));result.put("orderStatus",order.get("status"));result.put("items",items(id));
        var shipments=store.rows("SELECT id,status,installation_required AS installationRequired,installation_status AS installationStatus,installation_completed_at AS installationCompletedAt,delivered_at AS deliveredAt FROM shipments WHERE order_id=? ORDER BY id",id);
        for(var shipment:shipments){shipment.put("canCompleteInstallation",staff&&installationReady(order,shipment));shipment.remove("deliveredAt");}
        result.put("shipments",shipments);
        var returns=store.rows("SELECT id,return_number AS returnNumber,status,resolution,reason,requested_at AS requestedAt,approved_by AS approvedBy,staff_note AS staffNote FROM return_requests WHERE order_id=? ORDER BY id DESC",id);
        for(var returned:returns){
            returned.put("canDecide",staff&&"REQUESTED".equals(returned.get("status")));
            returned.put("canReceive",staff&&"APPROVED".equals(returned.get("status"))&&returned.get("approvedBy")!=null&&Set.of("PROCESSING","COMPLETED").contains(order.get("status")));
            var lines=store.rows("SELECT ri.id,ri.order_item_id AS orderItemId,oi.sku,oi.product_name AS productName,ri.quantity,ri.received_quantity AS receivedQuantity,ri.restocked_quantity AS restockedQuantity,ri.condition_note AS conditionNote FROM return_items ri JOIN order_items oi ON oi.id=ri.order_item_id AND oi.order_id=ri.order_id WHERE ri.return_request_id=? ORDER BY ri.id",number(returned,"id"));
            if(!staff){returned.remove("approvedBy");returned.remove("staffNote");lines.forEach(line->line.remove("conditionNote"));}
            returned.put("items",lines);
        }
        result.put("returns",returns);return result;
    }

    private List<Map<String,Object>> items(long id){
        var lines=store.rows("""
            SELECT oi.id,oi.sku,oi.product_name AS productName,oi.quantity,
              (SELECT COALESCE(SUM(si.quantity),0) FROM shipment_items si JOIN shipments s ON s.id=si.shipment_id AND s.order_id=si.order_id
                WHERE si.order_item_id=oi.id AND si.order_id=oi.order_id AND s.status='DELIVERED' AND s.delivered_at IS NOT NULL) AS deliveredQuantity,
              (SELECT COALESCE(SUM(ri.quantity),0) FROM return_items ri JOIN return_requests rr ON rr.id=ri.return_request_id AND rr.order_id=ri.order_id
                WHERE ri.order_item_id=oi.id AND ri.order_id=oi.order_id AND rr.status IN ('REQUESTED','APPROVED','IN_TRANSIT','RECEIVED','COMPLETED')) AS claimedQuantity
            FROM order_items oi WHERE oi.order_id=? ORDER BY oi.id
            """,id);
        // MariaDB SUM(INT) returns DECIMAL; OrderStore formats decimals for money. Quantities stay JSON integers.
        for(var line:lines){
            int delivered=integer(line,"deliveredQuantity"),claimed=integer(line,"claimedQuantity");
            line.put("deliveredQuantity",delivered);line.put("claimedQuantity",claimed);
            line.put("returnableQuantity",Math.max(0,Math.min(integer(line,"quantity"),delivered)-claimed));
        }
        return lines;
    }

    private long returnOrder(long id){var rows=store.jdbc().queryForList("SELECT order_id FROM return_requests WHERE id=?",Long.class,id);if(rows.isEmpty())throw CommerceException.notFound("Không tìm thấy yêu cầu trả hàng.");return rows.get(0);}
    private Map<String,Object> lockedReturn(long id,long order){var rows=store.rows("SELECT id,return_number AS returnNumber,status,approved_by AS approvedBy FROM return_requests WHERE id=? AND order_id=? FOR UPDATE",id,order);if(rows.isEmpty())throw CommerceException.notFound("Không tìm thấy yêu cầu trả hàng.");return rows.get(0);}

    /** Action names and JSON property names are fixed internal constants, never request input. */
    private String proof(long order,String actions,String property,long entity){
        var rows=store.jdbc().queryForList("SELECT JSON_UNQUOTE(JSON_EXTRACT(after_data,'$.fingerprint')) FROM audit_logs WHERE entity_type='ORDER' AND entity_id=? AND action IN ('"+actions+"') AND JSON_UNQUOTE(JSON_EXTRACT(after_data,'$."+property+"'))=? ORDER BY id LIMIT 1",String.class,Long.toString(order),Long.toString(entity));
        return rows.isEmpty()?null:rows.get(0);
    }

    private static List<RequestedItem> requestedItems(List<RequestedItem> lines){
        if(lines==null||lines.isEmpty()||lines.size()>1000)throw invalid("items","Chọn các sản phẩm cần trả.");
        var ids=new HashSet<Long>();
        for(var line:lines)if(line==null||line.orderItemId()==null||line.orderItemId()<=0||line.quantity()==null||line.quantity()<=0||line.quantity()>1_000_000||!ids.add(line.orderItemId()))throw invalid("items","Mỗi dòng hàng chỉ được chọn một lần với số lượng dương hợp lệ.");
        return lines.stream().sorted(Comparator.comparing(RequestedItem::orderItemId)).toList();
    }

    private static List<ReceivedItem> receivedItems(List<ReceivedItem> lines){
        if(lines==null||lines.isEmpty()||lines.size()>1000)throw invalid("items","Cần ghi nhận tất cả các dòng hàng trả.");
        var ids=new HashSet<Long>();var normalized=new ArrayList<ReceivedItem>();long received=0;
        for(var line:lines){
            if(line==null||line.returnItemId()==null||line.returnItemId()<=0||line.receivedQuantity()==null||line.restockQuantity()==null||line.receivedQuantity()<0||line.restockQuantity()<0||line.restockQuantity()>line.receivedQuantity()||!ids.add(line.returnItemId())||clean(line.conditionNote()).length()>1000)throw invalid("items","Số lượng và ghi chú kiểm hàng không hợp lệ.");
            received+=line.receivedQuantity();normalized.add(new ReceivedItem(line.returnItemId(),line.receivedQuantity(),line.restockQuantity(),clean(line.conditionNote())));
        }
        if(received<1)throw invalid("items","Cần xác nhận ít nhất một sản phẩm thực nhận.");
        normalized.sort(Comparator.comparing(ReceivedItem::returnItemId));return normalized;
    }

    private static boolean installationReady(Map<String,Object> order,Map<String,Object> shipment){return Set.of("PROCESSING","COMPLETED").contains(order.get("status"))&&bool(order.get("assemblyRequested"))&&"DELIVERED".equals(shipment.get("status"))&&shipment.get("deliveredAt")!=null&&bool(shipment.get("installationRequired"))&&Set.of("PENDING","SCHEDULED").contains(shipment.get("installationStatus"));}
    private static boolean bool(Object value){return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()!=0;}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static int integer(Map<String,Object> row,String key){Object value=row.get(key);return value instanceof Number n?n.intValue():new java.math.BigDecimal(value.toString()).intValueExact();}
    private static String clean(String value){return value==null?"":value.trim();}
    private static String required(String value,String field){value=clean(value);if(value.isEmpty()||value.length()>1000)throw invalid(field,"Nhập lý do từ 1 đến 1000 ký tự.");return value;}
    private static void attest(Boolean value){if(!Boolean.TRUE.equals(value))throw invalid("acknowledged","Cần xác nhận đã đối chiếu bằng chứng thực tế.");}
    private static CommerceException invalid(String field,String message){return CommerceException.invalid(Map.of(field,message));}
    private static CommerceException conflict(String code){return CommerceException.conflict(code,switch(code){case "RETURN_QUANTITY_EXCEEDED"->"Số lượng vượt phần đã giao còn có thể yêu cầu trả.";case "RETURN_NOT_READY"->"Chỉ có thể trả hàng đã được giao thực tế.";case "IDEMPOTENCY_CONFLICT"->"Khóa yêu cầu đã dùng với nội dung khác.";case "RETURN_RECEIPT_CONFLICT"->"Yêu cầu đã được nhận với bằng chứng khác.";case "INSTALLATION_ALREADY_COMPLETED"->"Lắp đặt đã hoàn tất với bằng chứng khác.";case "INSTALLATION_NOT_READY"->"Vận đơn chưa giao hoặc không có dịch vụ lắp đặt đang chờ.";case "WAREHOUSE_INACTIVE"->"Kho không tồn tại hoặc đã ngừng hoạt động.";default->"Trạng thái hoặc bằng chứng đã thay đổi. Vui lòng tải lại.";});}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
}
