package org.example.eshop.commerce.engagement;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// API quản lý danh sách yêu thích của khách hàng đang đăng nhập
@RestController
@RequestMapping("/api/wishlist")
public class WishlistController {
    private final WishlistService service;

    public WishlistController(WishlistService service) {
        this.service = service;
    }

    // Lấy danh sách yêu thích của user hiện tại
    @GetMapping
    public List<WishlistService.WishlistItemView> list() {
        return service.listMine();
    }

    // Thêm sản phẩm vào mục yêu thích
    @PostMapping("/{productId}")
    @ResponseStatus(HttpStatus.CREATED)
    public List<WishlistService.WishlistItemView> add(@PathVariable long productId) {
        return service.add(productId);
    }

    // Xóa sản phẩm khỏi mục yêu thích
    @DeleteMapping("/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable long productId) {
        service.remove(productId);
    }
}
