package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Map;

/** Sandbox-only configuration. Never serialize this object or its secrets. */
@Component
public class SePaySettings {
    public static final String PROVIDER = "SEPAY_SANDBOX";
    private final boolean enabled;
    private final String secret, token, bank, account, holder;

    public SePaySettings(@Value("${commerce.payments.sepay.enabled:false}") boolean enabled,
            @Value("${commerce.payments.sepay.webhook-secret:}") String secret,
            @Value("${commerce.payments.sepay.api-token:}") String token,
            @Value("${commerce.payments.sepay.bank:VietinBank}") String bank,
            @Value("${commerce.payments.sepay.account-number:}") String account,
            @Value("${commerce.payments.sepay.account-holder:}") String holder) {
        this.enabled=enabled; this.secret=secret; this.token=token;
        this.bank=bank; this.account=account; this.holder=holder;
        if(enabled && (secret.length()<32 || secret.length()>512 || !safe(secret) ||
                !"VietinBank".equals(bank) || !account.matches("[0-9]{6,19}") ||
                holder.isBlank() || holder.length()>150 || !safe(holder) || !safe(token)))
            throw new IllegalStateException("SePay Sandbox requires a HMAC secret, VietinBank test account and account holder. Check private environment settings.");
    }
    private static boolean safe(String s){return s.codePoints().noneMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT);}
    public boolean enabled(){return enabled;}
    public String bank(){return bank;}
    public String account(){return account;}
    public String holder(){return holder;}
    String secret(){return secret;}
    String token(){return token;}
    public Map<String,Object> publicView(){return Map.of("enabled",enabled,"environment","SANDBOX","canReconcile",enabled&&!token.isBlank());}
    public void requireEnabled(){if(!enabled)throw CommerceException.conflict("SEPAY_NOT_CONFIGURED","Chưa bật cấu hình SePay Sandbox.");}
    public void validateAmount(BigDecimal amount){
        requireEnabled();
        if(amount.stripTrailingZeros().scale()>0 || amount.compareTo(new BigDecimal("1000"))<0 || amount.compareTo(new BigDecimal("499000000"))>0)
            throw CommerceException.conflict("SEPAY_AMOUNT_UNSUPPORTED","SePay QR cần số tiền nguyên từ 1.000 đến 499.000.000 VND. Vui lòng chọn phương thức khác.");
    }
}
