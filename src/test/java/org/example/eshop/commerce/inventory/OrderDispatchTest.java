package org.example.eshop.commerce.inventory;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.port.CommerceActor;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.example.eshop.commerce.port.InventoryPort;
import org.example.eshop.commerce.order.*;
import org.example.eshop.commerce.integration.JdbcOrderAuditAdapter;
import tools.jackson.databind.ObjectMapper;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "TV3_TEST_DB_URL", matches = ".*furniture_shop_test_.*")
@SpringJUnitConfig(OrderDispatchTest.Config.class)
class OrderDispatchTest {
    private static final long ACTOR = 931001;
    private static final long OWNER = 931002;
    private static final long ORDER = 931001;
    private static final long ITEM = 931001;
    private static final long VARIANT = 931001;
    private static final long WAREHOUSE = 931001;

    @TestConfiguration
    @EnableTransactionManagement
    @Import({OrderDispatchService.class, ShipmentLifecycleService.class, OrderCompletionPolicy.class, OrderLifecycleService.class,
        OrderStore.class, OrderAdminAccess.class, OrderLifecyclePolicy.class, AdminOrderQueryService.class,
        OrderQueryService.class, OrderCancellationService.class, JdbcOrderAuditAdapter.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("TV3_TEST_DB_URL"),
                    System.getenv().getOrDefault("DB_USERNAME", "root"), System.getenv("DB_PASSWORD"));
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean CheckoutQuoteService quoteBoundary() { return mock(CheckoutQuoteService.class); }
        @Bean InventoryPort cancellationBoundary() { return mock(InventoryPort.class); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC); }
        @Bean CurrentActorPort actor() {
            return new CurrentActorPort() {
                @Override public CommerceActor currentActor() { return new CommerceActor(ACTOR, null, Set.of("STAFF")); }
                @Override public void clearGuestIdentity() {}
            };
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired OrderDispatchService service;
    @Autowired ShipmentLifecycleService lifecycle;

    @Test void dispatchFromPendingUsesAuditedForwardLifecycleEdges() {
        jdbc.update("UPDATE orders SET status='PENDING_CONFIRMATION' WHERE id=?",ORDER);
        service.dispatch(ORDER,new OrderDispatchRequest(WAREHOUSE,"Local","PENDING-DISPATCH","Xuất kho"),"pending-dispatch-audited");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id=? AND from_status='PENDING_CONFIRMATION' AND to_status='CONFIRMED'",Integer.class,ORDER)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id=? AND from_status='CONFIRMED' AND to_status='PROCESSING'",Integer.class,ORDER)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id=? AND from_status='PENDING_CONFIRMATION' AND to_status='PROCESSING'",Integer.class,ORDER)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_type='ORDER' AND entity_id=? AND action='ORDER_STATUS_CHANGED'",Integer.class,Long.toString(ORDER))).isEqualTo(2);
    }
    @Autowired OrderLifecycleService orderLifecycle;

    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).startsWith("furniture_shop_test_");
        cleanup();
        jdbc.update("INSERT INTO users(id,username,email,password_hash,full_name) VALUES(931001,'tv4-dispatch-staff','tv4-dispatch-staff@example.invalid','hash','Staff'),(931002,'tv4-dispatch-owner','tv4-dispatch-owner@example.invalid','hash','Owner')");
        jdbc.update("INSERT INTO categories(id,name,slug) VALUES(931001,'TV4 Dispatch','tv4-dispatch')");
        jdbc.update("INSERT INTO products(id,category_id,name,slug,status) VALUES(931001,931001,'TV4 Chair','tv4-chair','ACTIVE')");
        jdbc.update("INSERT INTO product_variants(id,product_id,sku,variant_key,name,price,status) VALUES(931001,931001,'TV4-CHAIR-TEST','default','Default',100.00,'ACTIVE')");
        jdbc.update("INSERT INTO warehouses(id,code,name,is_active) VALUES(931001,'TV4-DISPATCH','Dispatch',1)");
        jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity,reserved_quantity) VALUES(931001,931001,10,4)");
        jdbc.update("INSERT INTO orders(id,order_number,user_id,idempotency_key,status,subtotal,grand_total) VALUES(931001,'TV4-ORDER-931001',931002,'tv4-order-key-931001','CONFIRMED',400.00,400.00)");
        jdbc.update("INSERT INTO order_items(id,order_id,product_id,variant_id,product_name,variant_name,sku,quantity,unit_price) VALUES(931001,931001,931001,931001,'TV4 Chair','Default','TV4-CHAIR-TEST',4,100.00)");
        jdbc.update("INSERT INTO inventory_reservations(order_item_id,warehouse_id,variant_id,quantity) VALUES(931001,931001,931001,4)");
        jdbc.update("INSERT INTO payments(order_id,method,provider,idempotency_key,amount,currency,status) VALUES(931001,'COD','COD','tv4-payment-key-931001',400.00,'VND','PENDING')");
    }

    @AfterEach void cleanup() {
        if (jdbc == null) return;
        jdbc.update("DELETE FROM audit_logs WHERE entity_type='SHIPMENT' AND entity_id IN (SELECT CAST(id AS CHAR) FROM shipments WHERE order_id=?)", ORDER);
        jdbc.update("DELETE FROM audit_logs WHERE entity_type='ORDER' AND entity_id=?", Long.toString(ORDER));
        jdbc.update("DELETE FROM refunds WHERE order_id=?", ORDER);
        jdbc.update("DELETE FROM inventory_movements WHERE variant_id=?", VARIANT);
        jdbc.update("DELETE FROM shipment_items WHERE order_id=?", ORDER);
        jdbc.update("DELETE FROM shipments WHERE order_id=?", ORDER);
        jdbc.update("DELETE FROM inventory_reservations WHERE order_item_id IN (931001,931002)");
        jdbc.update("DELETE FROM payments WHERE order_id=?", ORDER);
        jdbc.update("DELETE FROM order_status_history WHERE order_id=?", ORDER);
        jdbc.update("DELETE FROM order_items WHERE order_id=?", ORDER);
        jdbc.update("DELETE FROM orders WHERE id=?", ORDER);
        jdbc.update("DELETE FROM inventory_balances WHERE variant_id=?", VARIANT);
        jdbc.update("DELETE FROM warehouses WHERE id IN (931001,931002)");
        jdbc.update("DELETE FROM product_variants WHERE id=?", VARIANT);
        jdbc.update("DELETE FROM products WHERE id=931001");
        jdbc.update("DELETE FROM categories WHERE id=931001");
        jdbc.update("DELETE FROM users WHERE id IN (?,?)", ACTOR, OWNER);
    }

    private OrderDispatchRequest request() { return new OrderDispatchRequest(WAREHOUSE, "Nội bộ", "TV4-VAN-001", "Giao chiều nay"); }

    @Test void dispatchConsumesHeldStockAndReplayDoesNotShipTwice() {
        assertThat(service.queue(0, 20).items()).anyMatch(item -> item.orderId() == ORDER && item.remainingQuantity() == 4);
        var preview = service.preview(ORDER);
        assertThat(preview.holds()).hasSize(1);
        assertThat(preview.holds().get(0).remainingQuantity()).isEqualTo(4);
        var shipped = service.dispatch(ORDER, request(), "dispatch-request-001");
        assertThat(shipped.replayed()).isFalse();
        assertThat(shipped.status()).isEqualTo("IN_TRANSIT");
        assertThat(shipped.items()).hasSize(1);
        assertThat(shipped.items().get(0).quantity()).isEqualTo(4);
        assertThat(service.dispatch(ORDER, request(), "dispatch-request-001").replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT on_hand_quantity FROM inventory_balances WHERE warehouse_id=? AND variant_id=?", Integer.class, WAREHOUSE, VARIANT)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM inventory_balances WHERE warehouse_id=? AND variant_id=?", Integer.class, WAREHOUSE, VARIANT)).isZero();
        assertThat(jdbc.queryForObject("SELECT fulfilled_quantity FROM inventory_reservations WHERE order_item_id=?", Integer.class, ITEM)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, ORDER)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id=? AND movement_type='SHIPMENT'", Integer.class, ITEM)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id=?", Integer.class, ORDER)).isEqualTo(1);
        assertThatThrownBy(() -> service.dispatch(ORDER, new OrderDispatchRequest(WAREHOUSE, "Nội bộ", "DIFFERENT", "Giao chiều nay"), "dispatch-request-001"))
                .isInstanceOfSatisfying(CommerceException.class, error -> assertThat(error.getCode()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test void twoOrderLinesWithSameSkuUpdateOneBalanceOnce() {
        jdbc.update("INSERT INTO order_items(id,order_id,product_id,variant_id,product_name,variant_name,sku,quantity,unit_price) VALUES(931002,931001,931001,931001,'TV4 Chair','Default','TV4-CHAIR-TEST',2,100.00)");
        jdbc.update("INSERT INTO inventory_reservations(order_item_id,warehouse_id,variant_id,quantity) VALUES(931002,931001,931001,2)");
        jdbc.update("UPDATE inventory_balances SET reserved_quantity=6 WHERE warehouse_id=? AND variant_id=?", WAREHOUSE, VARIANT);
        var result = service.dispatch(ORDER, request(), "dispatch-request-005");
        assertThat(result.items()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT on_hand_quantity FROM inventory_balances WHERE warehouse_id=? AND variant_id=?", Integer.class, WAREHOUSE, VARIANT)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM inventory_balances WHERE warehouse_id=? AND variant_id=?", Integer.class, WAREHOUSE, VARIANT)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE variant_id=? AND movement_type='SHIPMENT'", Integer.class, VARIANT)).isEqualTo(2);
    }

    @Test void dispatchRejectsUnverifiedTransferAndKeepsHold() {
        jdbc.update("UPDATE payments SET method='BANK_TRANSFER',provider='MANUAL_BANK' WHERE order_id=?", ORDER);
        assertThatThrownBy(() -> service.dispatch(ORDER, request(), "dispatch-request-002"))
                .isInstanceOfSatisfying(CommerceException.class, error -> assertThat(error.getCode()).isEqualTo("PAYMENT_UNVERIFIED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipments WHERE order_id=?", Integer.class, ORDER)).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM inventory_balances WHERE warehouse_id=? AND variant_id=?", Integer.class, WAREHOUSE, VARIANT)).isEqualTo(4);
    }

    @Test void cancelledOrReleasedOrderCannotBeDispatched() {
        jdbc.update("UPDATE orders SET status='CANCELLED' WHERE id=?", ORDER);
        assertThatThrownBy(() -> service.dispatch(ORDER, request(), "dispatch-request-003"))
                .isInstanceOfSatisfying(CommerceException.class, error -> assertThat(error.getCode()).isEqualTo("ORDER_NOT_DISPATCHABLE"));
        jdbc.update("UPDATE orders SET status='CONFIRMED' WHERE id=?", ORDER);
        jdbc.update("UPDATE inventory_reservations SET released_quantity=4 WHERE order_item_id=?", ITEM);
        jdbc.update("UPDATE inventory_balances SET reserved_quantity=0 WHERE warehouse_id=? AND variant_id=?", WAREHOUSE, VARIANT);
        assertThatThrownBy(() -> service.dispatch(ORDER, request(), "dispatch-request-004"))
                .isInstanceOfSatisfying(CommerceException.class, error -> assertThat(error.getCode()).isEqualTo("NO_HELD_STOCK"));
    }

    @Test void paidDeliveryCompletesOrderThroughSharedAuditOnceAndPreservesInventory() {
        collectPayment();
        long shipment = service.dispatch(ORDER, request(), "dispatch-request-006").shipmentId();
        var delivered = lifecycle.changeStatus(shipment,
                new ShipmentStatusRequest("DELIVERED", "Khách đã nhận hàng"), "delivery-request-001");
        assertThat(delivered.status()).isEqualTo("DELIVERED");
        assertThat(delivered.deliveredAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, ORDER)).isEqualTo("COMPLETED");
        assertThat(lifecycle.changeStatus(shipment,
                new ShipmentStatusRequest("DELIVERED", "Khách đã nhận hàng"), "delivery-request-001").replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_type='SHIPMENT' AND entity_id=?", Integer.class, Long.toString(shipment))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id=? AND to_status='COMPLETED'", Integer.class, ORDER)).isEqualTo(1);
        assertThat(completionAudits()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT on_hand_quantity FROM inventory_balances WHERE warehouse_id=? AND variant_id=?", Integer.class, WAREHOUSE, VARIANT)).isEqualTo(6);
    }

    @Test void failedDeliveryCanBeRetriedThenDelivered() {
        collectPayment();
        long shipment = service.dispatch(ORDER, request(), "dispatch-request-007").shipmentId();
        assertThat(lifecycle.changeStatus(shipment,
                new ShipmentStatusRequest("FAILED", "Không liên lạc được"), "delivery-request-002").status()).isEqualTo("FAILED");
        assertThat(lifecycle.changeStatus(shipment,
                new ShipmentStatusRequest("IN_TRANSIT", "Hẹn giao lại"), "delivery-request-003").status()).isEqualTo("IN_TRANSIT");
        assertThat(lifecycle.changeStatus(shipment,
                new ShipmentStatusRequest("DELIVERED", "Đã giao lần hai"), "delivery-request-004").status()).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_type='SHIPMENT' AND entity_id=?", Integer.class, Long.toString(shipment))).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, ORDER)).isEqualTo("COMPLETED");
    }

    @Test void returnedShipmentRestocksOnceAndLeavesOrderForAfterSales() {
        long shipment = service.dispatch(ORDER, request(), "dispatch-request-008").shipmentId();
        lifecycle.changeStatus(shipment, new ShipmentStatusRequest("FAILED", "Khách từ chối nhận"), "delivery-request-005");
        var returned = lifecycle.changeStatus(shipment,
                new ShipmentStatusRequest("RETURNED", "Hàng đã về kho"), "delivery-request-006");
        assertThat(returned.status()).isEqualTo("RETURNED");
        assertThat(jdbc.queryForObject("SELECT on_hand_quantity FROM inventory_balances WHERE warehouse_id=? AND variant_id=?", Integer.class, WAREHOUSE, VARIANT)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT reserved_quantity FROM inventory_balances WHERE warehouse_id=? AND variant_id=?", Integer.class, WAREHOUSE, VARIANT)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id=? AND movement_type='RETURN'", Integer.class, ITEM)).isEqualTo(1);
        assertThat(lifecycle.changeStatus(shipment,
                new ShipmentStatusRequest("RETURNED", "Hàng đã về kho"), "delivery-request-006").replayed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT on_hand_quantity FROM inventory_balances WHERE warehouse_id=? AND variant_id=?", Integer.class, WAREHOUSE, VARIANT)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, ORDER)).isEqualTo("PROCESSING");
        assertThatThrownBy(() -> lifecycle.changeStatus(shipment,
                new ShipmentStatusRequest("DELIVERED", "Không hợp lệ"), "delivery-request-007"))
                .isInstanceOfSatisfying(CommerceException.class, error -> assertThat(error.getCode()).isEqualTo("SHIPMENT_STATUS_CONFLICT"));
    }

    @Test void deliveryOfOneWarehouseDoesNotCompleteAnotherWarehouseShipment() {
        collectPayment();
        jdbc.update("UPDATE inventory_reservations SET quantity=2 WHERE order_item_id=?", ITEM);
        jdbc.update("UPDATE inventory_balances SET reserved_quantity=2 WHERE warehouse_id=? AND variant_id=?", WAREHOUSE, VARIANT);
        jdbc.update("INSERT INTO warehouses(id,code,name,is_active) VALUES(931002,'TV4-DISPATCH-2','Second',1)");
        jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity,reserved_quantity) VALUES(931002,931001,5,2)");
        jdbc.update("INSERT INTO inventory_reservations(order_item_id,warehouse_id,variant_id,quantity) VALUES(931001,931002,931001,2)");
        long first = service.dispatch(ORDER, request(), "dispatch-request-009").shipmentId();
        long second = service.dispatch(ORDER, new OrderDispatchRequest(931002L,"Nội bộ","TV4-VAN-002","Đợt hai"), "dispatch-request-010").shipmentId();
        lifecycle.changeStatus(first, new ShipmentStatusRequest("DELIVERED", "Đợt một đã giao"), "delivery-request-008");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, ORDER)).isEqualTo("PROCESSING");
        lifecycle.changeStatus(second, new ShipmentStatusRequest("DELIVERED", "Đợt hai đã giao"), "delivery-request-009");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?", String.class, ORDER)).isEqualTo("COMPLETED");
    }

    @Test void failedRestockRollsBackStatusAndCanRetrySameKey() {
        long shipment = service.dispatch(ORDER, request(), "dispatch-request-011").shipmentId();
        lifecycle.changeStatus(shipment, new ShipmentStatusRequest("FAILED", "Khách vắng nhà"), "delivery-request-010");
        jdbc.update("UPDATE inventory_balances SET on_hand_quantity=2147483647 WHERE warehouse_id=? AND variant_id=?", WAREHOUSE, VARIANT);
        var returned = new ShipmentStatusRequest("RETURNED", "Hàng đã về kho");
        assertThatThrownBy(() -> lifecycle.changeStatus(shipment, returned, "delivery-request-011"))
                .isInstanceOfSatisfying(CommerceException.class, error -> assertThat(error.getCode()).isEqualTo("STOCK_LIMIT"));
        assertThat(jdbc.queryForObject("SELECT status FROM shipments WHERE id=?", String.class, shipment)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movements WHERE order_item_id=? AND movement_type='RETURN'", Integer.class, ITEM)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_type='SHIPMENT' AND request_id LIKE '%delivery-request-011'", Integer.class)).isZero();
        jdbc.update("UPDATE inventory_balances SET on_hand_quantity=6 WHERE warehouse_id=? AND variant_id=?", WAREHOUSE, VARIANT);
        assertThat(lifecycle.changeStatus(shipment, returned, "delivery-request-011").status()).isEqualTo("RETURNED");
    }
    private void collectPayment() {
        jdbc.update("UPDATE payments SET status='SUCCEEDED',paid_at=UTC_TIMESTAMP(6) WHERE order_id=?",ORDER);
    }
    private int completionAudits() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE entity_type='ORDER' AND entity_id=? AND action='ORDER_STATUS_CHANGED' AND JSON_UNQUOTE(JSON_EXTRACT(after_data,'$.status'))='COMPLETED'",Integer.class,Long.toString(ORDER));
    }
    @Test void unpaidCodDeliverySucceedsAndKeepsOrderProcessing() {
        long shipment=service.dispatch(ORDER,request(),"unpaid-dispatch-key").shipmentId();
        var result=lifecycle.changeStatus(shipment,new ShipmentStatusRequest("DELIVERED","Khách đã nhận hàng"),"unpaid-delivery-key");
        assertThat(result.status()).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,ORDER)).isEqualTo("PROCESSING");
        assertThat(completionAudits()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id=? AND to_status='COMPLETED'",Integer.class,ORDER)).isZero();
        assertThat(lifecycle.changeStatus(shipment,new ShipmentStatusRequest("DELIVERED","Khách đã nhận hàng"),"unpaid-delivery-key").replayed()).isTrue();
    }
    @Test void deliveredPaymentReservedForRefundCannotAutomaticallyComplete() {
        collectPayment();
        long payment=jdbc.queryForObject("SELECT id FROM payments WHERE order_id=?",Long.class,ORDER);
        jdbc.update("INSERT INTO refunds(order_id,payment_id,idempotency_key,amount,status,reason) VALUES(?,?,?,1,'PENDING','Khoản đang chờ hoàn')",ORDER,payment,"dispatch-refund-key");
        long shipment=service.dispatch(ORDER,request(),"refunded-dispatch-key").shipmentId();
        assertThat(lifecycle.changeStatus(shipment,new ShipmentStatusRequest("DELIVERED","Đã nhận hàng"),"refunded-delivery-key").status()).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,ORDER)).isEqualTo("PROCESSING");
        assertThat(completionAudits()).isZero();
    }
    @Test void selectedFreeAssemblyPropagatesAndBlocksCompletionUntilInstallationProof() {
        collectPayment();
        jdbc.update("UPDATE orders SET assembly_requested=1 WHERE id=?",ORDER);
        jdbc.update("UPDATE order_items SET assembly_required=1 WHERE id=?",ITEM);
        long shipment=service.dispatch(ORDER,request(),"free-assembly-dispatch").shipmentId();
        assertThat(jdbc.queryForObject("SELECT assembly_fee FROM orders WHERE id=?",java.math.BigDecimal.class,ORDER)).isZero();
        assertThat(jdbc.queryForObject("SELECT installation_required FROM shipments WHERE id=?",Boolean.class,shipment)).isTrue();
        assertThat(jdbc.queryForObject("SELECT installation_status FROM shipments WHERE id=?",String.class,shipment)).isEqualTo("PENDING");
        assertThat(lifecycle.changeStatus(shipment,new ShipmentStatusRequest("DELIVERED","Đã giao, chờ lắp đặt"),"free-assembly-delivery").status()).isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,ORDER)).isEqualTo("PROCESSING");
        assertThat(completionAudits()).isZero();
        jdbc.update("UPDATE shipments SET installation_status='COMPLETED',installation_completed_at=UTC_TIMESTAMP(6),installation_staff_id=? WHERE id=?",ACTOR,shipment);
        orderLifecycle.transition(ORDER,"PROCESSING","COMPLETED","Đã hoàn tất lắp đặt");
        orderLifecycle.transition(ORDER,"PROCESSING","COMPLETED","Đã hoàn tất lắp đặt");
        assertThat(completionAudits()).isEqualTo(1);
    }
    @Test void technicallyRequiredButUnrequestedAssemblyIsNotScheduled() {
        collectPayment();
        jdbc.update("UPDATE order_items SET assembly_required=1 WHERE id=?",ITEM);
        long shipment=service.dispatch(ORDER,request(),"unselected-assembly-dispatch").shipmentId();
        assertThat(jdbc.queryForObject("SELECT installation_required FROM shipments WHERE id=?",Boolean.class,shipment)).isFalse();
        assertThat(jdbc.queryForObject("SELECT installation_status FROM shipments WHERE id=?",String.class,shipment)).isEqualTo("NOT_REQUIRED");
        lifecycle.changeStatus(shipment,new ShipmentStatusRequest("DELIVERED","Không chọn lắp đặt"),"unselected-assembly-delivery");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=?",String.class,ORDER)).isEqualTo("COMPLETED");
        assertThat(completionAudits()).isEqualTo(1);
    }
    @Test void installationRequirementUsesOnlyLinesDispatchedFromThisWarehouse() {
        jdbc.update("UPDATE orders SET assembly_requested=1,subtotal=600,grand_total=600 WHERE id=?",ORDER);
        jdbc.update("UPDATE order_items SET assembly_required=1 WHERE id=?",ITEM);
        jdbc.update("INSERT INTO order_items(id,order_id,product_id,variant_id,product_name,variant_name,sku,quantity,unit_price,assembly_required) VALUES(931002,931001,931001,931001,'TV4 Chair','Default','TV4-CHAIR-TEST',2,100,0)");
        jdbc.update("INSERT INTO warehouses(id,code,name,is_active) VALUES(931002,'TV4-DISPATCH-2','Second',1)");
        jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity,reserved_quantity) VALUES(931002,931001,5,2)");
        jdbc.update("INSERT INTO inventory_reservations(order_item_id,warehouse_id,variant_id,quantity) VALUES(931002,931002,931001,2)");
        long ordinary=service.dispatch(ORDER,new OrderDispatchRequest(931002L,"Nội bộ","TV4-NO-ASSEMBLY","Không cần lắp"),"warehouse-no-assembly").shipmentId();
        long technical=service.dispatch(ORDER,request(),"warehouse-with-assembly").shipmentId();
        assertThat(jdbc.queryForObject("SELECT installation_required FROM shipments WHERE id=?",Boolean.class,ordinary)).isFalse();
        assertThat(jdbc.queryForObject("SELECT installation_required FROM shipments WHERE id=?",Boolean.class,technical)).isTrue();
    }
}
