package org.example.eshop.commerce.payment;

import org.example.eshop.commerce.common.CommerceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.UUID;

/** No redirects or configurable live hosts: a Test mode token only reaches sandbox. */
@Component
public class SePayApiClient {
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String token;
    private final URI base;
    private final Duration timeout;
    @Autowired public SePayApiClient(SePaySettings settings,ObjectMapper mapper){
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build(),mapper,settings.token(),URI.create("https://userapi-sandbox.sepay.vn/v2/"),Duration.ofSeconds(5));
    }
    SePayApiClient(HttpClient http,ObjectMapper mapper,String token,URI base,Duration timeout){this.http=http;this.mapper=mapper;this.token=token;this.base=base;this.timeout=timeout;}
    public SePayTransfer transaction(String id){
        if(id==null||!id.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))throw CommerceException.invalid(java.util.Map.of("transactionId","Nhập UUID giao dịch từ SePay API v2."));
        id=UUID.fromString(id).toString();
        if(token.isBlank())throw CommerceException.conflict("SEPAY_API_NOT_CONFIGURED","Chưa cấu hình API Token SePay Sandbox.");
        try{
            var request=HttpRequest.newBuilder(base.resolve("transactions/"+id)).timeout(timeout).header("Authorization","Bearer "+token).header("Accept","application/json").header("User-Agent","InteriorManagement-Sandbox/1.0").GET().build();
            var future=http.sendAsync(request,info->new LimitedBodySubscriber(65536));
            HttpResponse<byte[]> response;
            try{response=future.get(timeout.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);}
            catch(java.util.concurrent.TimeoutException|java.util.concurrent.ExecutionException e){future.cancel(true);throw unavailable();}
            catch(InterruptedException e){future.cancel(true);throw e;}
            if(response.statusCode()==404)throw CommerceException.notFound("SEPAY_TRANSACTION_NOT_FOUND","Không tìm thấy giao dịch SePay cho UUID này. Kiểm tra lại UUID.");
            if(response.statusCode()!=200)throw unavailable();
            var root=mapper.readTree(response.body());
            if(root==null||!"success".equals(root.path("status").asText())||!root.path("data").isObject())throw unavailable();
            try{return SePayTransfer.api(root.get("data"),id);}catch(RuntimeException e){throw unavailable();}
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}
        catch(IllegalArgumentException|tools.jackson.core.JacksonException e){throw unavailable();}
    }
    private static CommerceException unavailable(){return CommerceException.conflict("SEPAY_API_UNAVAILABLE","Không xác minh được giao dịch từ SePay. Chưa thay đổi khoản thu; hãy thử lại sau.");}
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int maximum;
        private java.util.concurrent.Flow.Subscription subscription;
        LimitedBodySubscriber(int maximum){this.maximum=maximum;}
        public java.util.concurrent.CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        public void onNext(java.util.List<java.nio.ByteBuffer> chunks){
            for(var chunk:chunks){if(bytes.size()+chunk.remaining()>maximum){subscription.cancel();result.completeExceptionally(new IOException("Response too large"));return;}byte[] b=new byte[chunk.remaining()];chunk.get(b);bytes.writeBytes(b);}
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
