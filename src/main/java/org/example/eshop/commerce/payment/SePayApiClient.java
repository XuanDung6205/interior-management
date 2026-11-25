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
import java.util.*;
import tools.jackson.databind.JsonNode;

/** No redirects or configurable live hosts: a Test mode token only reaches sandbox. */
@Component
public class SePayApiClient {
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String token;
    private final URI base;
    private final Duration timeout;
    private final boolean paced;
    private long nextRequestNanos;
    @Autowired public SePayApiClient(SePaySettings settings,ObjectMapper mapper){
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build(),mapper,settings.token(),URI.create("https://userapi-sandbox.sepay.vn/v2/"),Duration.ofSeconds(5),true);
    }
    SePayApiClient(HttpClient http,ObjectMapper mapper,String token,URI base,Duration timeout){this(http,mapper,token,base,timeout,false);}
    private SePayApiClient(HttpClient http,ObjectMapper mapper,String token,URI base,Duration timeout,boolean paced){this.http=http;this.mapper=mapper;this.token=token;this.base=base;this.timeout=timeout;this.paced=paced;}
    public record ProviderResponse(int status,JsonNode body){}
    public record SearchResult(List<SePayTransfer> transfers,boolean complete){}
    private static String uuid(String id){if(id==null||!id.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"))throw CommerceException.invalid(Map.of("transactionId","Nhập UUID giao dịch từ SePay API v2."));return UUID.fromString(id).toString();}
    public SePayTransfer transaction(String id){
        id=uuid(id);var response=send("transactions/"+id,null,null);
        if(response.status()==404)throw CommerceException.notFound("SEPAY_TRANSACTION_NOT_FOUND","Không tìm thấy giao dịch SePay cho UUID này. Kiểm tra lại UUID.");
        if(response.status()!=200||!success(response.body())||!response.body().path("data").isObject())throw unavailable();
        try{return SePayTransfer.api(response.body().get("data"),id);}catch(RuntimeException e){throw unavailable();}
    }
    /** Bounded lookup: incomplete or malformed pagination can never prove absence. */
    public SearchResult searchCode(String code){
        if(code==null||!code.matches("DH[0-9]{8}"))throw CommerceException.invalid(Map.of("code","Mã thanh toán không hợp lệ."));
        var response=send("transactions?q="+code+"&page=1&per_page=100",null,null);var root=response.body();
        if(response.status()!=200||!success(root)||!root.path("data").isArray()||root.path("data").size()>100)throw unavailable();
        var data=root.path("data");var pg=root.path("meta").path("pagination");
        boolean complete=pg.path("has_more").isBoolean()&&!pg.path("has_more").asBoolean()&&pg.path("current_page").isIntegralNumber()&&pg.path("current_page").asInt()==1&&pg.path("total").isIntegralNumber()&&pg.path("total").asLong()==data.size()&&pg.path("last_page").isIntegralNumber()&&pg.path("last_page").asInt()<=1&&pg.path("last_page").asInt()>=0;
        var transfers=new ArrayList<SePayTransfer>();var seen=new HashSet<String>();
        for(var row:data){try{String id=uuid(row.path("id").asText());var t=SePayTransfer.api(row,id);if(!seen.add(id))complete=false;transfers.add(t);}catch(RuntimeException e){complete=false;}}
        return new SearchResult(List.copyOf(transfers),complete);
    }
    public ProviderResponse refundRequest(String transactionId,long amount,String key){
        if(amount<=0||amount>499000000||key==null||!key.matches("[A-Za-z0-9:_-]{1,100}"))throw CommerceException.invalid(Map.of("refund","Yêu cầu hoàn tiền không hợp lệ."));
        return send("transactions/"+uuid(transactionId)+"/refund","{\"refund_amount\":"+amount+"}",key);
    }
    public ProviderResponse refundDetails(String bankAccountId,String refundId){
        if(refundId==null||!refundId.matches("[A-Za-z0-9-]{1,100}"))throw CommerceException.invalid(Map.of("refundId","Mã hoàn tiền không hợp lệ."));
        return send("bank-accounts/"+uuid(bankAccountId)+"/refunds/"+refundId,null,null);
    }
    private static boolean success(JsonNode root){return root!=null&&"success".equals(root.path("status").asText());}
    private synchronized void pace()throws InterruptedException{if(!paced)return;long wait=nextRequestNanos-System.nanoTime();if(wait>0)java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);nextRequestNanos=System.nanoTime()+500_000_000L;}
    private ProviderResponse send(String path,String body,String key){
        if(token.isBlank())throw CommerceException.conflict("SEPAY_API_NOT_CONFIGURED","Chưa cấu hình API Token SePay Sandbox.");
        try{
            pace();var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).header("Authorization","Bearer "+token).header("Accept","application/json").header("User-Agent","InteriorManagement-Sandbox/1.0");
            if(body==null)builder.GET();else builder.header("Content-Type","application/json").header("X-Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(body));
            var request=builder.build();
            var future=http.sendAsync(request,info->new LimitedBodySubscriber(65536));
            HttpResponse<byte[]> response;
            try{response=future.get(timeout.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);}
            catch(java.util.concurrent.TimeoutException|java.util.concurrent.ExecutionException e){future.cancel(true);throw unavailable();}
            catch(InterruptedException e){future.cancel(true);throw e;}
            var root=mapper.readTree(response.body());
            if(root==null||!root.isObject())throw unavailable();return new ProviderResponse(response.statusCode(),root);
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
