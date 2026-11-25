package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** Opt-in executable probe for the local Postman simulation; not a skipped CI test. */
public class SePaySimulationProbe {
    public static void main(String[] args){
        if(args.length!=1||!args[0].matches("http://127\\.0\\.0\\.1:[0-9]+/sepay/v2/"))throw new IllegalArgumentException("Local simulation URI required");
        var client=new SePayApiClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),new ObjectMapper(),"simulation-only-token",URI.create(args[0]),Duration.ofSeconds(3));
        var transfer=client.transaction("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        if(!"2050000.00".equals(transfer.amount().toPlainString())||!"DH00000001".equals(transfer.code()))throw new AssertionError("Documented fields not normalized");
        int failed=0;
        for(String id:new String[]{"00000000-0000-0000-0000-000000000001","00000000-0000-0000-0000-000000000002"}){
            try{client.transaction(id);throw new AssertionError("Bad upstream response accepted");}catch(CommerceException e){if(!"SEPAY_API_UNAVAILABLE".equals(e.getCode()))throw e;failed++;}
        }
        System.out.println("Postman simulation: 1 documented transfer and "+failed+" upstream failures verified using the real Java HTTP client.");
    }
}
