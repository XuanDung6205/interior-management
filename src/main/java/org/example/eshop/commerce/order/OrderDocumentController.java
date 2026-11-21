package org.example.eshop.commerce.order;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OrderDocumentController {
    private final OrderDocumentService documents;
    private final Clock clock;
    public OrderDocumentController(OrderDocumentService documents,Clock clock){this.documents=documents;this.clock=clock;}
    @GetMapping("/orders/{id}/print") public ResponseEntity<Map<String,Object>> customer(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(documents.customer(id));
    }
    @GetMapping("/admin/orders/{id}/print") public ResponseEntity<Map<String,Object>> staff(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(documents.staff(id));
    }
    @GetMapping("/admin/orders/export.csv") public ResponseEntity<byte[]> csv(
            @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String query,
            @RequestParam(defaultValue="") String fromDate,@RequestParam(defaultValue="") String toDate) {
        byte[] body=documents.csv(status,query,fromDate,toDate);
        String stamp=DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(clock.instant());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("orders-"+stamp+".csv").build().toString())
                .header("X-Content-Type-Options","nosniff").body(body);
    }
}
