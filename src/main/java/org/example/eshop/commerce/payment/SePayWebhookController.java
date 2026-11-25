package org.example.eshop.commerce.payment;

import jakarta.servlet.http.HttpServletRequest;
import org.example.eshop.commerce.common.CommerceException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;

@RestController
public class SePayWebhookController {
    private final SePaySignature signature;
    private final SePayEventService events;
    private final ObjectMapper mapper;
    public SePayWebhookController(SePaySignature signature,SePayEventService events,ObjectMapper mapper){this.signature=signature;this.events=events;this.mapper=mapper;}
    @PostMapping(value="/api/payments/sepay/webhook",consumes="application/json",produces="application/json")
    public Object receive(HttpServletRequest request)throws IOException{
        byte[] body=request.getInputStream().readNBytes(65537);
        if(body.length>65536)throw CommerceException.badRequest("SEPAY_PAYLOAD_TOO_LARGE","Webhook vượt kích thước cho phép.");
        signature.verify(request.getHeader("X-SePay-Timestamp"),request.getHeader("X-SePay-Signature"),body);
        SePayTransfer transfer;
        try{transfer=SePayTransfer.webhook(mapper.readTree(body));}catch(CommerceException e){throw e;}catch(RuntimeException e){throw SePayTransfer.invalid();}
        events.process(transfer,null);
        return java.util.Map.of("success",true);
    }
}
