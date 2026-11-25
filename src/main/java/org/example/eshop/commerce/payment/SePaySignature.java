package org.example.eshop.commerce.payment;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

@Component
public class SePaySignature {
    private final SePaySettings settings;
    private final Clock clock;
    public SePaySignature(SePaySettings settings,Clock clock){this.settings=settings;this.clock=clock;}
    public void verify(String timestamp,String signature,byte[] body){
        settings.requireEnabled();
        if(timestamp==null || !timestamp.matches("[0-9]{1,12}") || signature==null || !signature.matches("sha256=[a-fA-F0-9]{64}"))throw unauthorized();
        long seconds=Long.parseLong(timestamp);
        if(Math.abs(clock.instant().getEpochSecond()-seconds)>300)throw unauthorized();
        try{
            var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(settings.secret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            mac.update((timestamp+".").getBytes(StandardCharsets.UTF_8));
            if(!MessageDigest.isEqual(mac.doFinal(body),HexFormat.of().parseHex(signature.substring(7))))throw unauthorized();
        }catch(java.security.GeneralSecurityException e){throw new IllegalStateException("HMAC unavailable",e);}
    }
    private static ResponseStatusException unauthorized(){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid SePay signature or timestamp");}
}
