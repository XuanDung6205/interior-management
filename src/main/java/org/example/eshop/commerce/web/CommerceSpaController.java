package org.example.eshop.commerce.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnResource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Browser routes are enabled only when the release includes the React build. */
@Controller
@ConditionalOnResource(resources = "classpath:/static/index.html")
public class CommerceSpaController {
    @GetMapping({"/", "/cart", "/login", "/checkout", "/orders", "/orders/{id:\\d+}",
            "/orders/{id:\\d+}/payments", "/orders/{id:\\d+}/returns", "/notifications",
            "/admin/coupons", "/admin/orders", "/admin/orders/{id:\\d+}",
            "/admin/orders/{id:\\d+}/refunds", "/admin/orders/{id:\\d+}/aftercare",
            "/admin/payments", "/admin/payments/{id:\\d+}", "/admin/refunds", "/admin/refunds/{id:\\d+}",
            "/admin/operations", "/admin/inventory/receipts", "/admin/inventory/operations",
            "/admin/inventory/dispatch", "/admin/inventory/shipments"})
    public String reactEntry() {
        return "forward:/index.html";
    }
}
