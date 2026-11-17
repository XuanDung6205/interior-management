package org.example.eshop.commerce.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Public manual-transfer instructions; no bank calls, merchant secrets or external QR services. */
@Component
public class BankTransferSettings {
    private enum Mode {UNCONFIGURED,DEMO,MANUAL}
    private final Mode mode;
    private final String bankName;
    private final String accountNumber;
    private final String accountHolder;

    public BankTransferSettings(JdbcTemplate jdbc,
            @Value("${commerce.payments.bank.mode:UNCONFIGURED}") String mode,
            @Value("${commerce.payments.bank.bank-name:}") String bankName,
            @Value("${commerce.payments.bank.account-number:}") String accountNumber,
            @Value("${commerce.payments.bank.account-holder:}") String accountHolder) {
        this.mode=parseMode(mode);
        if(this.mode==Mode.DEMO) {
            String database=jdbc.queryForObject("SELECT DATABASE()",String.class);
            if(database==null || !(database.equals("furniture_shop_tv3_dev") || database.matches("furniture_shop_test_[A-Za-z0-9_]+"))) {
                throw new IllegalStateException("Chế độ chuyển khoản DEMO chỉ được bật trên cơ sở dữ liệu thử nghiệm hoặc demo riêng của TV3.");
            }
            this.bankName="NGÂN HÀNG MINH HỌA - KHÔNG CHUYỂN TIỀN";
            this.accountNumber="DEMO-KHONG-CHUYEN-TIEN";
            this.accountHolder="TÀI KHOẢN DEMO - KHÔNG CHUYỂN TIỀN";
        } else if(this.mode==Mode.MANUAL) {
            this.bankName=required(bankName,100,"Tên ngân hàng");
            this.accountNumber=required(accountNumber,64,"Số tài khoản");
            this.accountHolder=required(accountHolder,150,"Tên chủ tài khoản");
            if(!this.accountNumber.matches("[0-9]{6,64}")) {
                throw new IllegalStateException("Số tài khoản chuyển khoản thủ công phải có từ 6 đến 64 chữ số ASCII. Đây chỉ là kiểm tra định dạng.");
            }
        } else {
            this.bankName=null;
            this.accountNumber=null;
            this.accountHolder=null;
        }
    }

    public Map<String,Object> forOrder(String orderNumber) {
        String notice=switch(mode) {
            case UNCONFIGURED -> "Chưa cấu hình thông tin chuyển khoản. Vui lòng không chuyển tiền khi chưa nhận được hướng dẫn chính thức của cửa hàng.";
            case DEMO -> "DEMO chỉ minh họa trên dữ liệu thử nghiệm, không phải sandbox ngân hàng. Không chuyển tiền thật theo thông tin này.";
            case MANUAL -> "Thông tin nhận chuyển khoản do cửa hàng cấu hình; hệ thống chỉ kiểm tra định dạng, không xác minh tài khoản ngân hàng. Thanh toán chỉ được ghi nhận sau khi nhân viên kiểm tra chứng từ.";
        };
        var instructions=new LinkedHashMap<String,Object>();
        instructions.put("mode",mode.name());
        instructions.put("configured",mode!=Mode.UNCONFIGURED);
        instructions.put("isDemo",mode==Mode.DEMO);
        instructions.put("notice",notice);
        instructions.put("bankName",bankName);
        instructions.put("accountNumber",accountNumber);
        instructions.put("accountHolder",accountHolder);
        instructions.put("transferContent",orderNumber);
        return Collections.unmodifiableMap(instructions);
    }

    private static Mode parseMode(String raw) {
        if(raw==null || unsafe(raw))throw new IllegalStateException("Chế độ chuyển khoản không hợp lệ.");
        try {return Mode.valueOf(raw.strip().toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException exception) {throw new IllegalStateException("Chế độ chuyển khoản phải là UNCONFIGURED, DEMO hoặc MANUAL.");}
    }
    private static String required(String raw,int maximum,String label) {
        // Validate before trimming so a newline/tab cannot disappear into a seemingly valid setting.
        if(raw==null || unsafe(raw))throw new IllegalStateException(label+" không được để trống hoặc chứa ký tự điều khiển.");
        String normalized=raw.strip();
        if(normalized.isBlank() || normalized.length()>maximum)throw new IllegalStateException(label+" phải có từ 1 đến "+maximum+" ký tự.");
        return normalized;
    }
    private static boolean unsafe(String value) {
        return value.codePoints().anyMatch(code -> {
            int type=Character.getType(code);
            return Character.isISOControl(code) || type==Character.FORMAT || type==Character.SURROGATE
                || type==Character.LINE_SEPARATOR || type==Character.PARAGRAPH_SEPARATOR;
        });
    }
}
