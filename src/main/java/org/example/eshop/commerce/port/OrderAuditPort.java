package org.example.eshop.commerce.port;
import java.util.Map;
public interface OrderAuditPort {
    void append(long actorId,long orderId,String action,Map<String,Object> before,Map<String,Object> after);
    void appendSystem(long orderId,String action,Map<String,Object> before,Map<String,Object> after);
}
