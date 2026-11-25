package org.example.eshop.commerce.payment;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** Provider evidence is accepted only for the immutable local refund intent. */
final class SePayRefundResponse {
    record Result(String state,String refundId,String error){boolean terminal(){return Set.of("SUCCEEDED","FAILED","REJECTED").contains(state);}}
    private static final Set<String> DEFINITIVE=Set.of("not_available","unsupported_bank","unsupported_currency","not_refundable","unsupported_amount_precision","validation_error","refund_declined","refund_amount_invalid","refund_failed","refund_rejected");
    static Result classify(int status,JsonNode body,long amount,String reference,String knownId){
        return classify(status,body,amount,reference,knownId,null);
    }
    static Result classify(int status,JsonNode body,long amount,String reference,String knownId,String expectedTransaction){
        if(body==null||!body.isObject())return unknown("INVALID_RESPONSE");
        if(status==422&&knownId==null&&"error".equals(body.path("status").asText())&&DEFINITIVE.contains(body.path("error_code").asText()))return new Result("FAILED",null,body.get("error_code").asText().toUpperCase(Locale.ROOT));
        if(status!=200)return unknown(status==409?"IDEMPOTENCY_RECONCILIATION_REQUIRED":"PROVIDER_UNAVAILABLE");
        JsonNode data=body.path("data");String id=data.path("refund_id").asText();
        if(!"success".equals(body.path("status").asText())||!data.isObject()||!id.matches("RF-[A-Za-z0-9-]{3,96}")
                ||knownId!=null&&!knownId.equals(id)||!"VND".equals(data.path("currency").asText())
                ||!data.path("amount").isIntegralNumber()||!data.path("amount").canConvertToLong()||data.path("amount").asLong()!=amount
                ||!sourceMatches(data,reference,expectedTransaction))return unknown("RESPONSE_MISMATCH");
        String state=data.path("status").asText().toUpperCase(Locale.ROOT);
        if(!Set.of("PENDING","SUCCEEDED","FAILED","REJECTED").contains(state))return unknown("INVALID_PROVIDER_STATE");
        return new Result(state,id,Set.of("FAILED","REJECTED").contains(state)?"PROVIDER_"+state:null);
    }
    private static boolean sourceMatches(JsonNode data,String reference,String expectedTransaction){
        var transaction=data.path("transaction_id");var original=data.path("original_reference");
        // Some Sandbox responses omit the bank reference. The immutable API UUID
        // is the alternative proof; conflicting supplied evidence is never ignored.
        if(expectedTransaction!=null&&SePayRefundSettings.uuid(transaction.asText())&&!expectedTransaction.equals(transaction.asText()))return false;
        boolean missingReference=original.isNull()||original.isMissingNode()||original.isTextual()&&original.asText().isBlank();
        if(missingReference)return SePayRefundSettings.uuid(expectedTransaction)&&transaction.isTextual()&&expectedTransaction.equals(transaction.asText());
        return original.isTextual()&&reference!=null&&reference.equals(original.asText());
    }
    private static Result unknown(String code){return new Result("UNKNOWN",null,code);}
}
