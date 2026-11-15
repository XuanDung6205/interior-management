package org.example.eshop.commerce.order;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class OrderLifecyclePolicy {
    public static final Set<String> STATUSES=Set.of("PENDING_CONFIRMATION","CONFIRMED","PROCESSING","COMPLETED","CANCELLED");
    private static final Map<String,List<String>> EDGES=Map.of("PENDING_CONFIRMATION",List.of("CONFIRMED","CANCELLED"),"CONFIRMED",List.of("PROCESSING","CANCELLED"),"PROCESSING",List.of("COMPLETED","CANCELLED"));
    private final OrderStore store;private final OrderCompletionPolicy completion;
    public OrderLifecyclePolicy(OrderStore store,OrderCompletionPolicy completion){this.store=store;this.completion=completion;}
    public void validateEdge(String from,String to){
        if(!STATUSES.contains(Objects.toString(from,""))||!STATUSES.contains(Objects.toString(to,"")))throw CommerceException.badRequest("INVALID_STATUS","Trạng thái đơn không hợp lệ.");
        if(!EDGES.getOrDefault(from,List.of()).contains(to))throw CommerceException.conflict("INVALID_TRANSITION","Không được chuyển từ "+from+" sang "+to+".");
    }
    public List<String> reasons(long id,String to){
        if("CANCELLED".equals(to))return store.dispatched(id)?List.of("Đơn đã xuất kho hoặc giao vận. Vui lòng chuyển sang quy trình hậu mãi."):List.of();
        if("PROCESSING".equals(to)){
            int total=store.jdbc().queryForObject("SELECT COUNT(*) FROM order_items WHERE order_id=?",Integer.class,id);
            int missing=store.jdbc().queryForObject("SELECT COUNT(*) FROM order_items i WHERE i.order_id=? AND i.quantity<>(SELECT COALESCE(SUM(r.quantity-r.released_quantity),0) FROM inventory_reservations r WHERE r.order_item_id=i.id)",Integer.class,id);
            return total==0||missing>0?List.of("Giữ hàng chưa bao phủ đầy đủ số lượng của từng sản phẩm."):List.of();
        }
        if("COMPLETED".equals(to))return completion.evaluate(id).reasons();
        return List.of();
    }
    public record Options(List<String> allowedTransitions,List<Map<String,Object>> blockedTransitions){}
    public Options options(long id,String status){
        var allowed=new ArrayList<String>();var blocked=new ArrayList<Map<String,Object>>();
        for(String to:EDGES.getOrDefault(status,List.of())){var why=reasons(id,to);if(why.isEmpty())allowed.add(to);else blocked.add(Map.of("toStatus",to,"reasons",why));}
        return new Options(List.copyOf(allowed),List.copyOf(blocked));
    }
}
