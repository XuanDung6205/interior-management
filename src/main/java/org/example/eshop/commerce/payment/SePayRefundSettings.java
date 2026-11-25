package org.example.eshop.commerce.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class SePayRefundSettings {
    private final SePaySettings sepay;
    private final boolean configured;
    public SePayRefundSettings(SePaySettings sepay,@Value("${commerce.payments.sepay.refunds-enabled:false}") boolean configured){this.sepay=sepay;this.configured=configured;}
    public boolean enabled(){return configured&&sepay.canReconcile();}
    static boolean claimed(Map<String,Object> refund){return refund.get("sepayRequestKey")!=null;}
    static boolean uuid(Object value){return value instanceof String s&&s.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}");}
    Map<String,Object> view(Map<String,Object> refund,Map<String,Object> payment){
        boolean claimed=claimed(refund),pending="PENDING".equals(refund.get("status"));
        String state=claimed?Objects.toString(refund.get("sepayExecutionState"),"UNKNOWN"):"NOT_STARTED";
        boolean verified=payment!=null&&SePaySettings.PROVIDER.equals(payment.get("provider"))&&"SUCCEEDED".equals(payment.get("status"))&&uuid(payment.get("sepayTransactionId"))&&uuid(payment.get("sepayBankAccountId"));
        var result=new LinkedHashMap<String,Object>();result.put("state",state);result.put("refundId",refund.get("sepayRefundId"));result.put("canSubmit",enabled()&&!claimed&&pending&&verified);result.put("canSync",enabled()&&claimed&&pending&&Set.of("UNKNOWN","PENDING").contains(state));
        result.put("message",message(state,enabled(),verified));return result;
    }
    private static String message(String state,boolean enabled,boolean verified){return switch(state){
        case "SUCCEEDED"->"SePay Sandbox đã xác minh hoàn tiền thành công.";
        case "FAILED","REJECTED"->"Lệnh hoàn Sandbox đã bị từ chối hoặc thất bại; yêu cầu này giữ nguyên lịch sử xử lý.";
        case "UNKNOWN"->"Chưa xác định kết quả. Khoản tiền vẫn được giữ; đối soát hoặc gửi lại đúng lệnh đã lưu, không hoàn thủ công.";
        case "PENDING"->"SePay Sandbox đang xử lý. Khoản tiền vẫn được giữ cho đến khi xác minh kết quả cuối.";
        default->!enabled?"Hoàn qua API Sandbox chưa bật. Có thể xác minh chứng từ thủ công nếu chưa gửi lệnh.":!verified?"Cần đối soát mã thanh toán để xác minh UUID giao dịch và tài khoản ngân hàng trước khi gửi lệnh.":"Có thể gửi khoản hoàn này qua SePay Sandbox sau khi đối chiếu nghiệp vụ.";
    };}
}
