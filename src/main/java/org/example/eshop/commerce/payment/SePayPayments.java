package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.order.OrderStore;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class SePayPayments {
    private final OrderStore store;
    private final SePaySettings settings;
    private final SePayAutomationSettings automation;
    public SePayPayments(OrderStore store,SePaySettings settings,SePayAutomationSettings automation){this.store=store;this.settings=settings;this.automation=automation;}
    /** Caller owns the order transaction. Payment auto-increment allocates never-reused codes. */
    public long insert(long order,BigDecimal amount,String key,LocalDateTime now){
        settings.validateAmount(amount);
        long id=store.insert("INSERT INTO payments(order_id,method,provider,idempotency_key,amount,currency,status,sepay_bank,sepay_account_number,sepay_account_holder,created_at,updated_at) VALUES(?,'BANK_TRANSFER',?,?,?,'VND','PENDING',?,?,?,?,?)",
                order,SePaySettings.PROVIDER,key,amount,settings.bank(),settings.account(),settings.holder(),now,now);
        if(id>99999999)throw CommerceException.conflict("SEPAY_CODE_EXHAUSTED","Đã hết dải mã SePay; chọn phương thức khác và liên hệ quản trị viên.");
        String code="DH"+String.format(Locale.ROOT,"%08d",id);
        store.jdbc().update("UPDATE payments SET sepay_code=?,sepay_content=?,expires_at=?,sepay_next_check_at=? WHERE id=?",code,"SEVQR "+code,now.plusSeconds(automation.ttlSeconds),now.plusSeconds(automation.intervalSeconds),id);
        return id;
    }
    public Map<String,Object> view(Map<String,Object> payment,boolean payable,boolean staff){
        var v=new LinkedHashMap<String,Object>();
        v.put("environment","SANDBOX");v.put("code",payment.get("sepayCode"));
        v.put("bank",payment.get("sepayBank"));v.put("accountNumber",payment.get("sepayAccountNumber"));
        v.put("accountHolder",payment.get("sepayAccountHolder"));v.put("transferContent",payment.get("sepayContent"));
        v.put("canReconcile",settings.enabled()&&!settings.token().isBlank());
        if(payable&&settings.enabled())v.put("qrUrl",staff?"/api/admin/payments/"+payment.get("id")+"/sepay/qr":"/api/orders/"+payment.get("orderId")+"/payments/"+payment.get("id")+"/sepay/qr");
        return v;
    }
}
