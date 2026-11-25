package org.example.eshop.commerce.payment;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class SePayRefundResponseTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private SePayRefundResponse.Result read(int status,String json,String known){return SePayRefundResponse.classify(status,mapper.readTree(json),50000,"BANK-ORIGINAL-123",known);}
    private String body(String state,String amount,String reference,String id){return "{\"status\":\"success\",\"data\":{\"refund_id\":\""+id+"\",\"original_reference\":\""+reference+"\",\"amount\":"+amount+",\"currency\":\"VND\",\"status\":\""+state+"\"}}";}
    private static final String TX="a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    private String transactionProof(String reference,String transaction){return "{\"status\":\"success\",\"data\":{\"refund_id\":\"RF-ONE-123\",\"amount\":50000,\"currency\":\"VND\",\"status\":\"succeeded\""+(reference==null?"":",\"original_reference\":"+reference)+(transaction==null?"":",\"transaction_id\":"+transaction)+"}}";}
    private SePayRefundResponse.Result bound(String json){return SePayRefundResponse.classify(200,mapper.readTree(json),50000,"BANK-ORIGINAL-123",null,TX);}
    @Test void onlyExactProviderProofCanSettleMoney(){
        var r=read(200,body("succeeded","50000","BANK-ORIGINAL-123","RF-ONE-123"),null);
        assertEquals("SUCCEEDED",r.state());assertEquals("RF-ONE-123",r.refundId());assertTrue(r.terminal());
        assertEquals("UNKNOWN",read(200,body("succeeded","50001","BANK-ORIGINAL-123","RF-ONE-123"),null).state());
        assertEquals("UNKNOWN",read(200,body("succeeded","50000","ANOTHER-REFERENCE","RF-ONE-123"),null).state());
        assertEquals("UNKNOWN",read(200,body("succeeded","50000","BANK-ORIGINAL-123","RF-OTHER-123"),"RF-ONE-123").state());
    }
    @Test void pendingRetainsProviderIdentityAndTerminalFailureRequiresSameProof(){
        var pending=read(200,body("pending","50000","BANK-ORIGINAL-123","RF-ONE-123"),null);
        assertEquals("PENDING",pending.state());assertFalse(pending.terminal());
        assertEquals("FAILED",read(200,body("failed","50000","BANK-ORIGINAL-123","RF-ONE-123"),"RF-ONE-123").state());
        assertEquals("REJECTED",read(200,body("rejected","50000","BANK-ORIGINAL-123","RF-ONE-123"),null).state());
    }
    @Test void serverErrorBusyKeyConflictAndMalformedSuccessNeverReleaseHeldBudget(){
        for(int status:new int[]{409,429,500,503})assertEquals("UNKNOWN",read(status,"{\"status\":\"error\",\"error_code\":\"unexpected_error\"}",null).state());
        for(String json:new String[]{"{}","null","{\"status\":\"success\",\"data\":{}}",body("succeeded","50000.5","BANK-ORIGINAL-123","RF-ONE-123"),body("created","50000","BANK-ORIGINAL-123","RF-ONE-123")})assertEquals("UNKNOWN",read(200,json,null).state());
        assertEquals("UNKNOWN",read(422,"{\"status\":\"error\",\"error_code\":\"already_refunded\"}",null).state());
    }
    @Test void verifiedInitialUnsupportedRequestCanFailButCannotEraseAcceptedProviderRecord(){
        String rejected="{\"status\":\"error\",\"error_code\":\"not_available\"}";
        assertEquals("FAILED",read(422,rejected,null).state());
        assertEquals("UNKNOWN",read(422,rejected,"RF-ONE-123").state());
    }
    @Test void sandboxMissingReferenceRequiresExactPersistedTransactionUuid(){
        for(String ref:new String[]{null,"null","\"\"","\"  \""})assertEquals("SUCCEEDED",bound(transactionProof(ref,"\""+TX+"\"")).state());
        assertEquals("UNKNOWN",bound(transactionProof("null",null)).state());
        assertEquals("UNKNOWN",bound(transactionProof("null","\"b1b2c3d4-e5f6-7890-abcd-ef1234567890\"")).state());
        assertEquals("UNKNOWN",bound(transactionProof("null","6398452")).state());
        assertEquals("UNKNOWN",bound(transactionProof("[]","\""+TX+"\"")).state());
        assertEquals("UNKNOWN",read(200,transactionProof("null","\""+TX+"\""),null).state());
    }
    @Test void suppliedWrongReferenceOrConflictingUuidCannotUseTheOtherProofToBypassMismatch(){
        assertEquals("UNKNOWN",bound(transactionProof("\"OTHER-BANK-REFERENCE\"","\""+TX+"\"")).state());
        assertEquals("UNKNOWN",bound(transactionProof("\"BANK-ORIGINAL-123\"","\"b1b2c3d4-e5f6-7890-abcd-ef1234567890\"")).state());
        assertEquals("SUCCEEDED",bound(transactionProof("\"BANK-ORIGINAL-123\"","6398452")).state());
        assertEquals("SUCCEEDED",bound(transactionProof("\"BANK-ORIGINAL-123\"",null)).state());
    }
}
