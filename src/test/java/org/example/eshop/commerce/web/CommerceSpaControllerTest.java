package org.example.eshop.commerce.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CommerceSpaControllerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CommerceSpaController()).build();

    @Test void knownBrowserDeepLinksForwardToTheReactEntry() throws Exception {
        for (String path : new String[]{"/", "/cart", "/login", "/checkout", "/orders", "/orders/123",
                "/orders/123/payments", "/orders/123/returns", "/orders/123/print", "/notifications", "/admin/coupons",
                "/admin/orders", "/admin/orders/123", "/admin/orders/123/refunds", "/admin/orders/123/aftercare", "/admin/orders/123/print",
                "/admin/payments", "/admin/payments/123", "/admin/refunds", "/admin/refunds/123",
                "/admin/operations", "/admin/inventory/receipts", "/admin/inventory/operations",
                "/admin/inventory/dispatch", "/admin/inventory/shipments"}) {
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test void apiAssetAndUnknownPathsAreNeverConsumedByTheSpaController() throws Exception {
        for (String path : new String[]{"/api/unknown", "/assets/missing.js", "/images/missing.png",
                "/index.html", "/admin/unknown", "/orders/not-an-id", "/orders/123/unknown"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }
}
