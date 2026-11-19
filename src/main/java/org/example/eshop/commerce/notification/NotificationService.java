package org.example.eshop.commerce.notification;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.order.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly=true)
public class NotificationService {
    private final OrderStore store;private final CheckoutQuoteService actors;private final Clock clock;
    public NotificationService(OrderStore store,CheckoutQuoteService actors,Clock clock){this.store=store;this.actors=actors;this.clock=clock;}
    public Map<String,Object> list(int page,int size,boolean unread){
        long actor=actors.actorId();if(page<0||size<1||size>100)throw CommerceException.badRequest("INVALID_PAGE","Phân trang không hợp lệ.");String where=" WHERE n.recipient_user_id=?"+(unread?" AND n.read_at IS NULL":"");long count=store.jdbc().queryForObject("SELECT COUNT(*) FROM commerce_notifications n"+where,Long.class,actor);
        var rows=store.rows("SELECT n.id,n.order_id AS orderId,o.order_number AS orderNumber,n.notification_type AS type,n.title,n.body,n.created_at AS createdAt,n.read_at AS readAt FROM commerce_notifications n JOIN orders o ON o.id=n.order_id"+where+" ORDER BY n.created_at DESC,n.id DESC LIMIT ? OFFSET ?",actor,size,(long)page*size);for(var row:rows)row.put("href","/orders/"+row.get("orderId"));
        return Map.of("items",rows,"page",page,"size",size,"totalElements",count,"totalPages",(count+size-1)/size,"unreadCount",unreadCount());
    }
    public long unreadCount(){return store.jdbc().queryForObject("SELECT COUNT(*) FROM commerce_notifications WHERE recipient_user_id=? AND read_at IS NULL",Long.class,actors.actorId());}
    @Transactional
    public Map<String,Object> read(long id){long actor=actors.actorId();store.jdbc().update("UPDATE commerce_notifications SET read_at=COALESCE(read_at,?) WHERE id=? AND recipient_user_id=?",LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),id,actor);var rows=store.rows("SELECT id,read_at AS readAt FROM commerce_notifications WHERE id=? AND recipient_user_id=?",id,actor);if(rows.isEmpty())throw CommerceException.notFound("Không tìm thấy thông báo của bạn.");return rows.get(0);}
}
