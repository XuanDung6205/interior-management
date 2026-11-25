package org.example.eshop.commerce.tracking;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Endpoint nhận sự kiện hành vi từ trình duyệt (chấp nhận cả khách lẫn tài khoản đăng nhập, yêu cầu CSRF)
@RestController
@RequestMapping("/api/tracking")
public class TrackingApiController {
    private final CommerceTrackingService service;

    public TrackingApiController(CommerceTrackingService service) {
        this.service = service;
    }

    // Trả 202 Accepted sau khi ghi nhận sự kiện hợp lệ, không trả ngược dữ liệu tracking nhạy cảm
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void record(@RequestBody CommerceTrackingService.BrowserEventRequest req) {
        service.recordBrowserEvent(req);
    }
}
