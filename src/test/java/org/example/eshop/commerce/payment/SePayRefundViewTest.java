package org.example.eshop.commerce.payment;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SePayRefundViewTest {
    private final SePaySettings sepay=new SePaySettings(true,"test-only-hmac-key-at-least-32-characters","test-token","VietinBank","0000000001","TEST ACCOUNT");
    private final Map<String,Object> payment=Map.of("provider","SEPAY_SANDBOX","status","SUCCEEDED","sepayTransactionId","a1b2c3d4-e5f6-7890-abcd-ef1234567890","sepayBankAccountId","b1b2c3d4-e5f6-7890-abcd-ef1234567890");
    @Test void untouchedRequestsExposeSandboxActionOnlyWhenConfiguredAndVerified(){
        var view=new SePayRefundSettings(sepay,true);var r=Map.<String,Object>of("status","PENDING");
        assertEquals("NOT_STARTED",view.view(r,payment).get("state"));assertTrue((boolean)view.view(r,payment).get("canSubmit"));assertFalse((boolean)view.view(r,payment).get("canSync"));
        assertFalse((boolean)new SePayRefundSettings(sepay,false).view(r,payment).get("canSubmit"));assertFalse((boolean)view.view(r,Map.of("provider","SEPAY_SANDBOX","status","SUCCEEDED")).get("canSubmit"));
    }
    @Test void claimedUnknownCanOnlyBeSyncedAndTerminalCannotBeResent(){
        var view=new SePayRefundSettings(sepay,true);var r=new LinkedHashMap<String,Object>();r.put("status","PENDING");r.put("sepayRequestKey","private-stable-intent");r.put("sepayExecutionState","UNKNOWN");
        assertFalse((boolean)view.view(r,payment).get("canSubmit"));assertTrue((boolean)view.view(r,payment).get("canSync"));assertFalse(view.view(r,payment).toString().contains("private-stable-intent"));
        r.put("status","SUCCEEDED");r.put("sepayExecutionState","SUCCEEDED");r.put("sepayRefundId","RF-ONE-123");assertFalse((boolean)view.view(r,payment).get("canSync"));assertEquals("RF-ONE-123",view.view(r,payment).get("refundId"));
    }
}
