package org.example.eshop.commerce.vector;

import org.example.eshop.commerce.order.OrderAdminAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/operations/vector")
public class VectorOperationsController {
    private final VectorRuntime runtime;
    private final OrderAdminAccess access;
    public VectorOperationsController(VectorRuntime runtime, OrderAdminAccess access) { this.runtime = runtime; this.access = access; }
    public record Reindex(Long afterId, Integer limit) {}
    public record Retry(String reason) {}
    @GetMapping public Object snapshot() { access.requireStaff(); return runtime.snapshot(); }
    @PostMapping("/reindex") public Object reindex(@RequestBody Reindex request) {
        long actor = access.requireStaff(); return runtime.enqueuePage(request.afterId(), request.limit(), actor);
    }
    @PostMapping("/run") public Object run() { access.requireStaff(); return runtime.runBatch(); }
    @PostMapping("/events/{id}/retry") public Object retry(@PathVariable long id, @RequestBody Retry request) {
        long actor = access.requireStaff(); return runtime.retry(id, request.reason(), actor);
    }
}
