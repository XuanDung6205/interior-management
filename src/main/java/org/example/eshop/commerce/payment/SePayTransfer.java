package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Shared business facts from the documented webhook and API v2 representations. */
public record SePayTransfer(String eventId,String bank,String account,String subAccount,
        String code,String content,String direction,BigDecimal amount,String reference,String transactionDate) {
    private static final Pattern CODE=Pattern.compile("(?<![A-Z0-9])DH[0-9]{8}(?![A-Z0-9])");
    public static SePayTransfer webhook(JsonNode n){
        String id=text(n,"id",100,true);
        if(!id.matches("[1-9][0-9]{0,18}|[a-fA-F0-9-]{36}"))throw invalid();
        return new SePayTransfer("webhook:"+id,text(n,"gateway",50,true),text(n,"accountNumber",64,true),text(n,"subAccount",64,false),text(n,"code",100,false),text(n,"content",2000,true),text(n,"transferType",10,true),money(n.get("transferAmount")),text(n,"referenceCode",150,false),text(n,"transactionDate",64,true));
    }
    public static SePayTransfer api(JsonNode n,String requestedId){
        if(!requestedId.equals(text(n,"id",100,true)))throw invalid();
        String direction=text(n,"transfer_type",10,true);
        BigDecimal in=money(n.get("amount_in")),out=money(n.get("amount_out"));
        if(!Set.of("in","out").contains(direction)||("in".equals(direction)?out:in).signum()!=0)throw invalid();
        return new SePayTransfer("api:"+requestedId,text(n,"bank_brand_name",50,true),text(n,"account_number",64,true),text(n,"va",64,false),text(n,"code",100,false),text(n,"transaction_content",2000,true),direction,"in".equals(direction)?in:out,text(n,"reference_number",150,false),text(n,"transaction_date",64,true));
    }
    public boolean validCode(){
        var found=CODE.matcher(content.toUpperCase(Locale.ROOT));var codes=new ArrayList<String>();while(found.find())codes.add(found.group());
        return code.matches("DH[0-9]{8}") && codes.size()==1 && codes.get(0).equals(code);
    }
    public boolean containsSnapshot(String snapshot){
        if(snapshot==null)return false;
        var pattern=Pattern.compile("(?<![A-Z0-9])"+Pattern.quote(snapshot.toUpperCase(Locale.ROOT))+"(?![A-Z0-9])");
        var found=pattern.matcher(content.toUpperCase(Locale.ROOT));return found.find()&&!found.find();
    }
    public Map<String,Object> facts(){return Map.of("bank",bank.toUpperCase(Locale.ROOT),"account",account,"subAccount",subAccount,"code",code,"content",content,"direction",direction,"amount",amount.toPlainString(),"reference",reference,"transactionDate",transactionDate);}
    private static BigDecimal money(JsonNode n){try{if(n==null||(!n.isNumber()&&!n.isTextual())||!n.asText().matches("[0-9]{1,15}(?:\\.[0-9]{1,2})?"))throw invalid();return new BigDecimal(n.asText()).setScale(2);}catch(ArithmeticException e){throw invalid();}}
    private static String text(JsonNode n,String key,int max,boolean required){var v=n.get(key);if(v==null||v.isNull()){if(required)throw invalid();return "";}if(!v.isValueNode()||v.isBoolean())throw invalid();String s=v.asText();if(s.length()>max||(required&&s.isBlank())||s.codePoints().anyMatch(Character::isISOControl))throw invalid();return s;}
    static CommerceException invalid(){return CommerceException.badRequest("SEPAY_INVALID_PAYLOAD","Dữ liệu giao dịch SePay không hợp lệ.");}
}
