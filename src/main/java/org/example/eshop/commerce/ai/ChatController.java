package org.example.eshop.commerce.ai;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// API trợ lý tư vấn nội thất (hỗ trợ cả tài khoản đăng nhập lẫn khách vãng lai theo cookie phiên)
@RestController
@RequestMapping("/api/commerce/chat")
public class ChatController {
    private final FurnitureChatService service;

    public ChatController(FurnitureChatService service) {
        this.service = service;
    }

    // Liệt kê các cuộc trò chuyện gần nhất của người dùng hiện tại
    @GetMapping
    public List<FurnitureChatService.ConversationSummary> list() {
        return service.listMine();
    }

    // Xem chi tiết 1 cuộc hội thoại (chỉ chủ sở hữu mới đọc được)
    @GetMapping("/{publicId}")
    public FurnitureChatService.ConversationDetail detail(@PathVariable String publicId) {
        return service.getConversation(publicId);
    }

    // Gửi câu hỏi tư vấn nội thất và nhận phản hồi kèm danh sách sản phẩm giá/tồn thật
    @PostMapping
    public FurnitureChatService.SendMessageResponse send(@RequestBody FurnitureChatService.SendMessageRequest req) {
        return service.sendMessage(req);
    }
}
