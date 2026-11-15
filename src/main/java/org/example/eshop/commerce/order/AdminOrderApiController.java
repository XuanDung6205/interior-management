package org.example.eshop.commerce.order;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/orders")
public class AdminOrderApiController {
    private final AdminOrderQueryService queries;private final OrderLifecycleService lifecycle;
    public AdminOrderApiController(AdminOrderQueryService queries,OrderLifecycleService lifecycle){this.queries=queries;this.lifecycle=lifecycle;}
    @GetMapping public Object list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String query){return queries.list(page,size,status,query);}
    @GetMapping("/{id}") public Object detail(@PathVariable long id){return queries.detail(id);}
    public record TransitionRequest(String fromStatus,String toStatus,String note){}
    @PostMapping("/{id}/transitions") public Object transition(@PathVariable long id,@RequestBody TransitionRequest r){return lifecycle.transition(id,r.fromStatus(),r.toStatus(),r.note());}
    public record InternalNoteRequest(String internalNote,String expectedInternalNote){}
    @PatchMapping("/{id}/internal-note") public Object note(@PathVariable long id,@RequestBody InternalNoteRequest r){return lifecycle.internalNote(id,r.internalNote(),r.expectedInternalNote());}
}
