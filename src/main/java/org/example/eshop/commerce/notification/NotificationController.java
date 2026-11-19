package org.example.eshop.commerce.notification;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service){this.service=service;}
    @GetMapping public Object list(@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="10")int size,@RequestParam(defaultValue="false")boolean unreadOnly){return service.list(page,size,unreadOnly);}
    @GetMapping("/unread-count")public Object count(){return Map.of("unreadCount",service.unreadCount());}
    @PostMapping("/{id}/read")public Object read(@PathVariable long id){return service.read(id);}
}
