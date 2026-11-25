package org.example.eshop.commerce.payment;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.example.eshop.commerce.common.CommerceException;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class SePayApiClientTest {
    HttpServer server;
    String response,auth,path,idempotencyKey,requestBody;
    int status=200;
    long bodyDelay;
    static final String ID="a1b2c3d4-e5f6-7890-abcd-ef1234567890";
    @BeforeEach void setup()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        response="{\"status\":\"success\",\"data\":{\"id\":\""+ID+"\",\"transaction_date\":\"2026-01-22 16:45:30\",\"account_number\":\"0000000001\",\"va\":null,\"transfer_type\":\"in\",\"amount_in\":2050000,\"amount_out\":0,\"transaction_content\":\"SEVQR DH00000001\",\"reference_number\":\"FT-TEST-1\",\"code\":\"DH00000001\",\"bank_brand_name\":\"VietinBank\"}}";
        server.createContext("/",exchange->{path=exchange.getRequestURI().toString();auth=exchange.getRequestHeaders().getFirst("Authorization");idempotencyKey=exchange.getRequestHeaders().getFirst("X-Idempotency-Key");requestBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);if(status==302)exchange.getResponseHeaders().add("Location","https://example.invalid/");byte[] data=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,data.length);try(var out=exchange.getResponseBody()){if(bodyDelay>0)try{Thread.sleep(bodyDelay);}catch(InterruptedException e){Thread.currentThread().interrupt();}out.write(data);}});server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    SePayApiClient client(){return new SePayApiClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),new ObjectMapper(),"test-api-token",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v2/"),Duration.ofMillis(200));}
    @Test void documentedV2TransactionIsFetchedAndNormalized(){var t=client().transaction(ID);assertEquals("0000000001",t.account());assertEquals("2050000.00",t.amount().toPlainString());assertEquals("DH00000001",t.code());assertEquals("Bearer test-api-token",auth);assertEquals("/v2/transactions/"+ID,path);}
    @Test void upstreamErrorsRedirectsMalformedAndOversizeNeverYieldTransfers(){for(int code:new int[]{401,404,429,500,302}){status=code;assertThrows(CommerceException.class,()->client().transaction(ID));}status=200;for(String bad:new String[]{"<html>bad</html>","{}","{\"status\":\"error\",\"data\":{}}","x".repeat(65537)}){response=bad;assertThrows(CommerceException.class,()->client().transaction(ID));}}
    @Test void mismatchedUuidAndOutgoingRepresentationCannotBeAcceptedAsIncoming(){response=response.replace(ID,"00000000-0000-0000-0000-000000000000");assertThrows(CommerceException.class,()->client().transaction(ID));}
    @Test void uuidValidationPreventsPathInjection(){assertThrows(CommerceException.class,()->client().transaction("../bank-accounts"));assertNull(path);}
    @Test void slowResponseBodyIsBoundedEvenAfterHeadersArrive(){bodyDelay=800;assertTimeoutPreemptively(Duration.ofMillis(600),()->assertThrows(CommerceException.class,()->client().transaction(ID)));}
    @Test void missingTransactionIsDefinitiveSoStaffCanCorrectUuid(){status=404;var e=assertThrows(CommerceException.class,()->client().transaction(ID));assertEquals("SEPAY_TRANSACTION_NOT_FOUND",e.getCode());}
    @Test void codeSearchRequiresExplicitConsistentPaginationBeforeItCanProveAbsence(){
        response="{\"status\":\"success\",\"data\":[],\"meta\":{\"pagination\":{\"current_page\":1,\"per_page\":100,\"total\":0,\"last_page\":1,\"has_more\":false}}}";
        var result=client().searchCode("DH00000001");assertTrue(result.complete());assertTrue(result.transfers().isEmpty());assertEquals("/v2/transactions?q=DH00000001&page=1&per_page=100",path);
        response="{\"status\":\"success\",\"data\":[]}";assertFalse(client().searchCode("DH00000001").complete());
        response="{\"status\":\"success\",\"data\":[],\"meta\":{\"pagination\":{\"current_page\":1,\"per_page\":100,\"total\":200,\"last_page\":2,\"has_more\":true}}}";assertFalse(client().searchCode("DH00000001").complete());
    }
    @Test void malformedSearchRowAndAmbiguousCodeCannotAuthorizeExpiry(){
        response="{\"status\":\"success\",\"data\":[{}],\"meta\":{\"pagination\":{\"current_page\":1,\"per_page\":100,\"total\":1,\"last_page\":1,\"has_more\":false}}}";assertFalse(client().searchCode("DH00000001").complete());
        assertThrows(CommerceException.class,()->client().searchCode("../x"));
    }
    @Test void refundTransportKeepsErrorStatusAndValidatesPaths(){
        response="{\"status\":\"error\",\"error\":\"not_available\"}";status=422;
        assertEquals(422,client().refundRequest(ID,1000,"refund-test-key").status());assertEquals("/v2/transactions/"+ID+"/refund",path);assertEquals("refund-test-key",idempotencyKey);assertEquals("{\"refund_amount\":1000}",requestBody);
        assertThrows(CommerceException.class,()->client().refundDetails(ID,"../x"));
        response="<html>error</html>";assertThrows(CommerceException.class,()->client().refundRequest(ID,1000,"refund-test-key"));
    }
}
