package org.example.eshop.commerce.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Browser routes are enabled only when the release includes the React build. */
@Controller
@ConditionalOnResource(resources = "classpath:/static/index.html")
public class CommerceSpaController {
    @GetMapping({"/", "/sale", "/categories/{slug}", "/collections/{slug}", "/admin/catalog", "/products/{slug}", "/cart", "/login", "/register", "/account", "/checkout", "/orders", "/orders/{id:\\d+}",
            "/orders/{id:\\d+}/payments", "/orders/{id:\\d+}/returns", "/orders/{id:\\d+}/print", "/notifications",
            "/admin", "/admin/coupons", "/admin/orders", "/admin/orders/{id:\\d+}",
            "/admin/orders/{id:\\d+}/refunds", "/admin/orders/{id:\\d+}/aftercare", "/admin/orders/{id:\\d+}/print",
            "/admin/payments", "/admin/payments/{id:\\d+}", "/admin/refunds", "/admin/refunds/{id:\\d+}",
            "/admin/operations", "/admin/inventory/receipts", "/admin/inventory/operations",
            "/admin/inventory/dispatch", "/admin/inventory/shipments",
            "/articles", "/articles/{slug}", "/wishlist", "/reviews",
            "/admin/content", "/admin/reviews", "/admin/dashboard"})
    public String reactEntry() {
        return "forward:/index.html";
    }
}
