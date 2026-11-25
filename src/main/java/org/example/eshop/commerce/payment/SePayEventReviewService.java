package org.example.eshop.commerce.payment;
import org.example.eshop.commerce.order.*;
import org.example.eshop.commerce.common.CommerceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
@Service
public class SePayEventReviewService {
    private final OrderStore store;private final OrderAdminAccess access;private final Clock clock;
    public SePayEventReviewService(OrderStore store,OrderAdminAccess access,Clock clock){this.store=store;this.access=access;this.clock=clock;}
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> review(long id,String note,String expected){
        long actor=access.requireStaff();if(note==null||note.isBlank()||note.trim().length()>1000||note.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n')||!"OPEN".equals(expected))throw CommerceException.invalid(Map.of("note","Nhập ghi chú kiểm tra từ 1 đến 1000 ký tự."));note=note.trim();
        var rows=store.rows("SELECT id,processing_status AS status,review_note AS reviewNote,reviewed_by AS reviewedBy,reviewed_at AS reviewedAt FROM payment_events WHERE id=? AND provider=? FOR UPDATE",id,SePaySettings.PROVIDER);if(rows.isEmpty())throw CommerceException.notFound("Không tìm thấy sự kiện SePay.");var row=rows.get(0);
        if(!Set.of("FAILED","IGNORED").contains(row.get("status")))throw CommerceException.conflict("SEPAY_EVENT_NOT_REVIEWABLE","Chỉ kiểm tra sự kiện lỗi hoặc chưa khớp mã thanh toán.");
        if(row.get("reviewedAt")!=null){if(!note.equals(row.get("reviewNote"))||((Number)row.get("reviewedBy")).longValue()!=actor)throw CommerceException.conflict("SEPAY_REVIEW_CONFLICT","Sự kiện đã có ghi chú kiểm tra. Không thể thay đổi.");}
        else store.jdbc().update("UPDATE payment_events SET review_note=?,reviewed_by=?,reviewed_at=? WHERE id=?",note,actor,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),id);
        return store.rows("SELECT e.id,e.payment_id AS paymentId,p.order_id AS orderId,e.provider_event_id AS providerEventId,e.processing_status AS status,e.error_message AS reason,e.received_at AS receivedAt,'REVIEWED' AS reviewStatus,e.review_note AS reviewNote,e.reviewed_by AS reviewedBy,e.reviewed_at AS reviewedAt,JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.code')) AS code,JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.reference')) AS reference,JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.amount')) AS amount FROM payment_events e LEFT JOIN payments p ON p.id=e.payment_id WHERE e.id=?",id).get(0);
    }
}
