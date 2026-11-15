package org.example.eshop.commerce.order;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class OrderAdminAccess {
    private final CurrentActorPort actors;
    private final OrderStore store;
    public OrderAdminAccess(CurrentActorPort actors,OrderStore store){this.actors=actors;this.store=store;}
    public long requireStaff(){
        var actor=actors.currentActor();
        if(actor.userId()==null||Collections.disjoint(actor.roles(),Set.of("ADMIN","STAFF")))throw CommerceException.forbidden("Bạn cần quyền nhân viên hoặc quản trị viên.");
        return actor.userId();
    }
    /** The owner is immutable; lock referenced users first so later history FKs cannot invert locks. */
    public Map<String,Object> lock(long id,long actor){
        var owners=store.jdbc().query("SELECT user_id FROM orders WHERE id=?",(rs,n)->(Long)rs.getObject(1),id);
        if(owners.isEmpty())throw CommerceException.notFound("Không tìm thấy đơn hàng.");
        Long owner=owners.get(0);var ids=new TreeSet<Long>();ids.add(actor);if(owner!=null)ids.add(owner);
        for(long user:ids)store.jdbc().queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,user);
        var order=store.find(id,true);
        Long current=store.jdbc().queryForObject("SELECT user_id FROM orders WHERE id=?",Long.class,id);
        if(!Objects.equals(owner,current))throw CommerceException.conflict("ORDER_OWNER_CHANGED","Chủ đơn đã thay đổi. Vui lòng tải lại.");
        return order;
    }
}
