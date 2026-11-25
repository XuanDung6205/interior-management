package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.CommerceApplication;
import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.security.CommercePrincipal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@TestPropertySource(properties="commerce.jobs.enabled=false")
@SpringBootTest(classes=CommerceApplication.class,properties={"commerce.payments.sepay.enabled=true","commerce.payments.sepay.refunds-enabled=true","commerce.payments.sepay.api-token=unit-test-token","commerce.payments.sepay.webhook-secret=test-only-hmac-key-at-least-32-characters","commerce.payments.sepay.account-number=0000000001","commerce.payments.sepay.account-holder=TEST ACCOUNT"})
@ActiveProfiles("commerce")
@EnabledIfEnvironmentVariable(named="TV3_TEST_DB_URL",matches=".*furniture_shop_test_.*")
class SePayRefundIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",()->System.getenv("TV3_TEST_DB_URL"));r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));r.add("spring.datasource.username",()->System.getenv().getOrDefault("DB_USERNAME","root"));}
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper mapper;
    @Autowired WebApplicationContext context;
    @Autowired SePayRefundService refunds;
    @org.springframework.test.context.bean.override.mockito.MockitoBean SePayApiClient provider;
    MockMvc mvc;
    final CommercePrincipal admin=new CommercePrincipal(101L,"admin","Admin",null,Set.of("ADMIN"));
    final CommercePrincipal customer=new CommercePrincipal(102L,"customer","Customer",null,Set.of("CUSTOMER"));
    static final String TX="a1b2c3d4-e5f6-7890-abcd-ef1234567890",BANK="b1b2c3d4-e5f6-7890-abcd-ef1234567890",REF="BANK-ORIGINAL-123";
    @BeforeEach void setup(){
        assertTrue(db.queryForObject("SELECT DATABASE()",String.class).startsWith("furniture_shop_test_"));db.execute("DROP TRIGGER IF EXISTS tv3_refund_audit_fail");
        for(String t:List.of("audit_logs","refunds","return_items","return_requests","payment_events","payments","shipment_items","shipments","inventory_movements","inventory_reservations","coupon_redemptions","order_status_history","order_addresses","order_items","orders","cart_items","carts","coupons","user_addresses","inventory_balances","product_images","variant_materials","product_variants","products","categories","user_roles","users"))db.update("DELETE FROM "+t);
        for(var actor:List.of(admin,customer)){db.update("INSERT INTO users(id,username,email,password_hash,full_name) VALUES(?,?,?,?,?)",actor.id(),actor.getUsername(),actor.getUsername()+"@example.invalid","unused",actor.getUsername());db.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",actor.id(),actor.roles().iterator().next());}
        db.update("INSERT INTO orders(id,order_number,user_id,idempotency_key,status,subtotal,grand_total) VALUES(1001,'RF-ORDER-1001',102,'refund-order-1001','CANCELLED',100000,100000)");
        var transfer=new SePayTransfer("api:"+TX,"VietinBank","0000000001","","DH00001001","SEVQR DH00001001","in",new BigDecimal("100000.00"),REF,"2026-11-25 10:00:00");
        String fingerprint="SEPAY-"+HexFormat.of().formatHex(hash(("VIETINBANK|0000000001|"+REF).getBytes(StandardCharsets.UTF_8)));
        db.update("INSERT INTO payments(id,order_id,method,provider,provider_transaction_id,idempotency_key,amount,status,sepay_code,sepay_bank,sepay_account_number,sepay_content,sepay_transaction_id,sepay_bank_account_id) VALUES(1001,1001,'BANK_TRANSFER','SEPAY_SANDBOX',?,'rf-payment-1001',100000,'SUCCEEDED','DH00001001','VietinBank','0000000001','SEVQR DH00001001',?,?)",fingerprint,TX,BANK);
        db.update("INSERT INTO payment_events(payment_id,provider,provider_event_id,event_type,payload,processing_status) VALUES(1001,'SEPAY_SANDBOX',?,'TRANSFER',?,'PROCESSED')","api:"+TX,mapper.writeValueAsString(transfer.facts()));
        db.update("INSERT INTO refunds(id,order_id,payment_id,provider,idempotency_key,amount,status,reason) VALUES(1001,1001,1001,'SEPAY_SANDBOX','rf-request-1001',50000,'PENDING','Hoàn một phần')");
        mvc=webAppContextSetup(context).apply(springSecurity()).build();
    }
    private byte[] hash(byte[] input){try{return java.security.MessageDigest.getInstance("SHA-256").digest(input);}catch(Exception e){throw new IllegalStateException(e);}}
    JsonNode response(MvcResult r)throws Exception{return mapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));}
    JsonNode submit(int code)throws Exception{return response(mvc.perform(post("/api/admin/refunds/1001/sepay/submit").with(user(admin)).with(csrf()).contentType("application/json").content("{\"acknowledged\":true}")).andExpect(status().is(code)).andReturn());}
    JsonNode sync()throws Exception{return response(mvc.perform(post("/api/admin/refunds/1001/sepay/sync").with(user(admin)).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isOk()).andReturn());}
    JsonNode item(JsonNode summary){return summary.get("items").get(0);}
    SePayApiClient.ProviderResponse proof(String state,long amount){return new SePayApiClient.ProviderResponse(200,mapper.readTree("{\"status\":\"success\",\"data\":{\"refund_id\":\"RF-ONE-123\",\"original_reference\":\""+REF+"\",\"amount\":"+amount+",\"currency\":\"VND\",\"status\":\""+state+"\"}}"));}
    @Test void authenticatedAttestedStaffAndVerifiedOriginalCollectionAreRequired()throws Exception{
        mvc.perform(post("/api/admin/refunds/1001/sepay/submit").with(csrf()).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/refunds/1001/sepay/submit").with(user(customer)).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/refunds/1001/sepay/submit").with(user(admin)).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/refunds/1001/sepay/submit").with(user(admin)).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        db.update("UPDATE payments SET sepay_transaction_id=NULL WHERE id=1001");submit(409);verifyNoInteractions(provider);
    }
    @Test void persistedIntentCommitsBeforeIoAndVerifiedPendingThenSuccessSettlesOnce()throws Exception{
        when(provider.refundRequest(eq(TX),eq(50000L),anyString())).thenAnswer(invocation->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertNotNull(db.queryForObject("SELECT sepay_request_key FROM refunds WHERE id=1001",String.class));return proof("pending",50000);});
        when(provider.refundDetails(BANK,"RF-ONE-123")).thenReturn(proof("succeeded",50000));
        var initial=item(submit(200));assertEquals("PENDING",initial.get("status").asText());assertEquals("PENDING",initial.at("/sepayExecution/state").asText());assertFalse(initial.get("canConfirm").asBoolean());
        var completed=item(sync());assertEquals("SUCCEEDED",completed.get("status").asText());assertEquals("RF-ONE-123",completed.get("reference").asText());sync();submit(200);
        verify(provider,times(1)).refundRequest(eq(TX),eq(50000L),anyString());verify(provider,times(1)).refundDetails(BANK,"RF-ONE-123");
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='SEPAY_REFUND_SUCCEEDED'",Integer.class));assertEquals("SUCCEEDED",db.queryForObject("SELECT status FROM payments WHERE id=1001",String.class));
    }
    @Test void timeoutKeepsBudgetAndImmutableKeyForRetryWhileManualResolutionIsBlocked()throws Exception{
        when(provider.refundRequest(eq(TX),eq(50000L),anyString())).thenThrow(CommerceException.conflict("SEPAY_API_UNAVAILABLE","Timeout"));
        var first=submit(200);String key=db.queryForObject("SELECT sepay_request_key FROM refunds WHERE id=1001",String.class);assertEquals("UNKNOWN",item(first).at("/sepayExecution/state").asText());assertEquals("50000.00",first.get("pendingRefundAmount").asText());
        mvc.perform(post("/api/admin/refunds/1001/confirm").with(user(admin)).with(csrf()).contentType("application/json").content("{\"reference\":\"NO-MANUAL-AFTER-SEND\",\"acknowledged\":true}")).andExpect(status().isConflict());
        mvc.perform(post("/api/admin/refunds/1001/cancel").with(user(admin)).with(csrf()).contentType("application/json").content("{\"reason\":\"Không được bỏ intent\",\"acknowledged\":true}")).andExpect(status().isConflict());
        doReturn(proof("succeeded",50000)).when(provider).refundRequest(TX,50000L,key);assertEquals("SUCCEEDED",item(sync()).get("status").asText());verify(provider,times(2)).refundRequest(TX,50000L,key);
    }
    @Test void mismatchedProofHoldsBudgetUntilSameKeyReturnsDefinitiveRejection()throws Exception{
        when(provider.refundRequest(eq(TX),eq(50000L),anyString())).thenReturn(proof("succeeded",50001));
        var mismatch=submit(200);assertEquals("PENDING",item(mismatch).get("status").asText());assertEquals("UNKNOWN",item(mismatch).at("/sepayExecution/state").asText());
        when(provider.refundRequest(eq(TX),eq(50000L),anyString())).thenReturn(new SePayApiClient.ProviderResponse(422,mapper.readTree("{\"status\":\"error\",\"error_code\":\"not_available\"}")));
        var failed=sync();assertEquals("FAILED",item(failed).get("status").asText());assertEquals("0.00",failed.get("pendingRefundAmount").asText());assertFalse(item(failed).get("canCancel").asBoolean());
    }
    @Test void definitiveFirstAttemptRejectionReleasesBudgetWithoutConvertingIntentToManual()throws Exception{
        when(provider.refundRequest(eq(TX),eq(50000L),anyString())).thenReturn(new SePayApiClient.ProviderResponse(422,mapper.readTree("{\"status\":\"error\",\"error_code\":\"not_available\"}")));
        var failed=submit(200);assertEquals("FAILED",item(failed).get("status").asText());assertEquals("0.00",failed.get("pendingRefundAmount").asText());assertFalse(item(failed).get("canCancel").asBoolean());
    }
    @Test void timeoutThenSameKeyDefinitive422ResolvesFailureWithoutNewSendIdentity()throws Exception{
        when(provider.refundRequest(eq(TX),eq(50000L),anyString())).thenThrow(CommerceException.conflict("SEPAY_API_UNAVAILABLE","Timeout"));submit(200);
        var rejection=new SePayApiClient.ProviderResponse(422,mapper.readTree("{\"status\":\"error\",\"error_code\":\"not_available\"}"));doReturn(rejection).when(provider).refundRequest(eq(TX),eq(50000L),anyString());
        String key=db.queryForObject("SELECT sepay_request_key FROM refunds WHERE id=1001",String.class);var result=sync();assertEquals("FAILED",item(result).get("status").asText());assertEquals("FAILED",item(result).at("/sepayExecution/state").asText());assertEquals("0.00",result.get("pendingRefundAmount").asText());assertFalse(item(result).get("canConfirm").asBoolean());verify(provider,times(2)).refundRequest(TX,50000L,key);
    }
    @Test void concurrentSubmitsShareOnePersistedIntentAndOneNetworkCall()throws Exception{
        var entered=new CountDownLatch(1);var released=new CountDownLatch(1);
        when(provider.refundRequest(eq(TX),eq(50000L),anyString())).thenAnswer(invocation->{entered.countDown();assertTrue(released.await(10,TimeUnit.SECONDS));return proof("pending",50000);});
        var pool=Executors.newFixedThreadPool(2);try{var first=pool.submit(()->submit(200));assertTrue(entered.await(10,TimeUnit.SECONDS));var second=pool.submit(()->submit(200));assertEquals("UNKNOWN",item(second.get(10,TimeUnit.SECONDS)).at("/sepayExecution/state").asText());released.countDown();first.get(10,TimeUnit.SECONDS);}finally{released.countDown();pool.shutdownNow();}
        verify(provider,times(1)).refundRequest(eq(TX),eq(50000L),anyString());assertEquals(1,db.queryForObject("SELECT sepay_attempts FROM refunds WHERE id=1001",Integer.class));
    }
    @Test void schedulerOnlyRetriesPersistedApprovedIntentsAfterBackoffAndLease()throws Exception{
        assertEquals(0,refunds.runDue().get("scanned"));verifyNoInteractions(provider);
        when(provider.refundRequest(eq(TX),eq(50000L),anyString())).thenThrow(CommerceException.conflict("SEPAY_API_UNAVAILABLE","Timeout"));submit(200);
        assertEquals(0,refunds.runDue().get("scanned"));String key=db.queryForObject("SELECT sepay_request_key FROM refunds WHERE id=1001",String.class);
        db.update("UPDATE refunds SET sepay_retry_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND,sepay_lease_until=UTC_TIMESTAMP()+INTERVAL 1 MINUTE WHERE id=1001");assertEquals(0,refunds.runDue().get("scanned"));
        db.update("UPDATE refunds SET sepay_lease_until=NULL WHERE id=1001");doReturn(proof("pending",50000)).when(provider).refundRequest(TX,50000L,key);var result=refunds.runDue();assertEquals(1,result.get("scanned"));assertEquals(1,result.get("pending"));verify(provider,times(2)).refundRequest(TX,50000L,key);
        assertEquals(0,refunds.runDue().get("scanned"));
    }
    @Test void auditFailureAfterProviderSuccessKeepsRecoverableIntentAndRetriesSameKey()throws Exception{
        when(provider.refundRequest(eq(TX),eq(50000L),anyString())).thenReturn(proof("succeeded",50000));
        db.execute("CREATE TRIGGER tv3_refund_audit_fail BEFORE INSERT ON audit_logs FOR EACH ROW BEGIN IF NEW.action='SEPAY_REFUND_SUCCEEDED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced provider refund audit failure'; END IF; END");
        try{assertThrows(Exception.class,()->submit(200));}finally{db.execute("DROP TRIGGER tv3_refund_audit_fail");}
        String key=db.queryForObject("SELECT sepay_request_key FROM refunds WHERE id=1001",String.class);assertNotNull(key);assertEquals("PENDING",db.queryForObject("SELECT status FROM refunds WHERE id=1001",String.class));assertNull(db.queryForObject("SELECT provider_refund_id FROM refunds WHERE id=1001",String.class));
        db.update("UPDATE refunds SET sepay_lease_until=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id=1001");assertEquals("SUCCEEDED",item(sync()).get("status").asText());verify(provider,times(2)).refundRequest(TX,50000L,key);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='SEPAY_REFUND_SUCCEEDED'",Integer.class));
    }
    @Test void existingPartialRequestsReserveOriginalCollectionBeforeAnyProviderCall()throws Exception{
        db.update("INSERT INTO refunds(order_id,payment_id,provider,idempotency_key,amount,status,reason) VALUES(1001,1001, 'SEPAY_SANDBOX','over-reserved-other',60000,'PENDING','Budget đã giữ')");
        var error=submit(409);assertEquals("REFUND_RECONCILIATION_REQUIRED",error.get("code").asText());verifyNoInteractions(provider);assertNull(db.queryForObject("SELECT sepay_request_key FROM refunds WHERE id=1001",String.class));
    }
    @Test void staleLeaseResponseCannotOverwriteANewerWorkerResult()throws Exception{
        var entered=new CountDownLatch(1);var released=new CountDownLatch(1);var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(provider.refundRequest(eq(TX),eq(50000L),anyString())).thenAnswer(invocation->{int call=calls.incrementAndGet();if(call==1){entered.countDown();assertTrue(released.await(10,TimeUnit.SECONDS));return proof("pending",50000);}if(call==2)throw CommerceException.conflict("SEPAY_API_UNAVAILABLE","Timeout");return proof("succeeded",50000);});
        var pool=Executors.newSingleThreadExecutor();try{var first=pool.submit(()->submit(200));assertTrue(entered.await(10,TimeUnit.SECONDS));db.update("UPDATE refunds SET sepay_lease_until=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id=1001");
            assertEquals("UNKNOWN",item(sync()).at("/sepayExecution/state").asText());var current=db.queryForMap("SELECT status,sepay_execution_state,sepay_refund_id,sepay_retry_at,sepay_checked_at,sepay_attempts FROM refunds WHERE id=1001");
            released.countDown();first.get(10,TimeUnit.SECONDS);assertEquals(current,db.queryForMap("SELECT status,sepay_execution_state,sepay_refund_id,sepay_retry_at,sepay_checked_at,sepay_attempts FROM refunds WHERE id=1001"));assertEquals("SUCCEEDED",item(sync()).get("status").asText());
        }finally{released.countDown();pool.shutdownNow();}
        String key=db.queryForObject("SELECT sepay_request_key FROM refunds WHERE id=1001",String.class);verify(provider,times(3)).refundRequest(TX,50000L,key);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE action='SEPAY_REFUND_SUCCEEDED'",Integer.class));
    }
    @Test void providerSubmissionRacingManualConfirmOrCancelHasOneExclusiveWinner()throws Exception{
        for(String action:List.of("confirm","cancel")){setup();reset(provider);when(provider.refundRequest(eq(TX),eq(50000L),anyString())).thenReturn(proof("pending",50000));
            var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
            try{var providerRequest=pool.submit(()->{start.await();return mvc.perform(post("/api/admin/refunds/1001/sepay/submit").with(user(admin)).with(csrf()).contentType("application/json").content("{\"acknowledged\":true}")).andReturn().getResponse().getStatus();});
                var manual=pool.submit(()->{start.await();String body="confirm".equals(action)?"{\"reference\":\"MANUAL-RACE-PROOF-001\",\"acknowledged\":true}":"{\"reason\":\"Chưa gửi lệnh\",\"acknowledged\":true}";return mvc.perform(post("/api/admin/refunds/1001/"+action).with(user(admin)).with(csrf()).contentType("application/json").content(body)).andReturn().getResponse().getStatus();});
                start.countDown();var statuses=new ArrayList<>(List.of(providerRequest.get(10,TimeUnit.SECONDS),manual.get(10,TimeUnit.SECONDS)));Collections.sort(statuses);assertEquals(List.of(200,409),statuses);
            }finally{pool.shutdownNow();}
            String key=db.queryForObject("SELECT sepay_request_key FROM refunds WHERE id=1001",String.class);if(key==null){verifyNoInteractions(provider);assertEquals("confirm".equals(action)?"SUCCEEDED":"CANCELLED",db.queryForObject("SELECT status FROM refunds WHERE id=1001",String.class));}
            else {verify(provider,times(1)).refundRequest(TX,50000L,key);assertEquals("PENDING",db.queryForObject("SELECT status FROM refunds WHERE id=1001",String.class));}
        }
    }
    @Test void nullSandboxBankReferenceSettlesOnlyWhenResponseBindsThePersistedTransactionUuid()throws Exception{
        String template="{\"status\":\"success\",\"data\":{\"refund_id\":\"RF-ONE-123\",\"original_reference\":null,\"transaction_id\":\"%s\",\"amount\":50000,\"currency\":\"VND\",\"status\":\"succeeded\"}}";
        when(provider.refundRequest(eq(TX),eq(50000L),anyString())).thenReturn(new SePayApiClient.ProviderResponse(200,mapper.readTree(template.formatted(BANK))));
        var mismatch=submit(200);assertEquals("PENDING",item(mismatch).get("status").asText());assertEquals("UNKNOWN",item(mismatch).at("/sepayExecution/state").asText());
        String key=db.queryForObject("SELECT sepay_request_key FROM refunds WHERE id=1001",String.class);
        when(provider.refundRequest(TX,50000L,key)).thenReturn(new SePayApiClient.ProviderResponse(200,mapper.readTree(template.formatted(TX))));
        assertEquals("SUCCEEDED",item(sync()).get("status").asText());verify(provider,times(2)).refundRequest(TX,50000L,key);
        assertEquals(TX,db.queryForObject("SELECT sepay_transaction_id FROM refunds WHERE id=1001",String.class));assertEquals(REF,db.queryForObject("SELECT sepay_original_reference FROM refunds WHERE id=1001",String.class));
    }
}
