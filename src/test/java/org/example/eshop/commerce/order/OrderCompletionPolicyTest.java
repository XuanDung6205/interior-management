package org.example.eshop.commerce.order;

import org.example.eshop.commerce.common.CommerceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringJUnitConfig(OrderCompletionPolicyTest.Config.class)
@EnabledIfEnvironmentVariable(named="TV3_TEST_DB_URL",matches=".*furniture_shop_test_tv3_policy_.*")
class OrderCompletionPolicyTest {
    static final long USER=920001,ORDER=920001,LINE=920001,VARIANT=920001,WAREHOUSE=920001,SHIPMENT=920001,PAYMENT=920001;
    @TestConfiguration @EnableTransactionManagement @Import(OrderCompletionPolicy.class)
    static class Config {
        @Bean CountingDataSource dataSource() {
            return new CountingDataSource(new DriverManagerDataSource(System.getenv("TV3_TEST_DB_URL"),System.getenv().getOrDefault("DB_USERNAME","root"),System.getenv("DB_PASSWORD")));
        }
        @Bean JdbcTemplate jdbc(CountingDataSource source) {return new JdbcTemplate(source);}
        @Bean PlatformTransactionManager transactionManager(CountingDataSource source) {return new DataSourceTransactionManager(source);}
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderCompletionPolicy policy;
    @Autowired CountingDataSource dataSource;
    @Autowired PlatformTransactionManager transactions;
    TransactionTemplate tx;

    @BeforeEach void seedCompleteOrder() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).startsWith("furniture_shop_test_tv3_policy_");
        tx=new TransactionTemplate(transactions);cleanup();
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO users(id,username,email,password_hash,full_name) VALUES(920001,'tv3-policy','policy@example.invalid','not-a-login-hash','Policy Customer')");
            jdbc.update("INSERT INTO categories(id,name,slug) VALUES(920001,'Policy Sofa','tv3-policy-sofa')");
            jdbc.update("INSERT INTO products(id,category_id,name,slug,status,assembly_required) VALUES(920001,920001,'Policy Sofa','tv3-policy-sofa','ACTIVE',1)");
            jdbc.update("INSERT INTO product_variants(id,product_id,sku,variant_key,name,price) VALUES(920001,920001,'TV3-POLICY-SKU','oak','Oak',100)");
            jdbc.update("INSERT INTO warehouses(id,code,name) VALUES(920001,'TV3-POLICY','Policy Warehouse')");
            jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity) VALUES(920001,920001,98)");
            jdbc.update("INSERT INTO orders(id,order_number,user_id,idempotency_key,status,subtotal,grand_total,assembly_requested) VALUES(920001,'TV3-POLICY-ORDER',920001,'tv3-policy-key','PROCESSING',200,200,0)");
            jdbc.update("INSERT INTO order_items(id,order_id,product_id,variant_id,product_name,variant_name,sku,quantity,unit_price,assembly_required) VALUES(920001,920001,920001,920001,'Policy Sofa','Oak','TV3-POLICY-SKU',2,100,1)");
            jdbc.update("INSERT INTO payments(id,order_id,method,provider,idempotency_key,amount,status,paid_at) VALUES(920001,920001,'COD','COD','tv3-policy-payment',200,'SUCCEEDED',UTC_TIMESTAMP(6))");
            jdbc.update("INSERT INTO shipments(id,order_id,warehouse_id,status,shipped_at,delivered_at) VALUES(920001,920001,920001,'DELIVERED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            jdbc.update("INSERT INTO shipment_items(shipment_id,order_id,order_item_id,quantity) VALUES(920001,920001,920001,2)");
            jdbc.update("INSERT INTO inventory_reservations(order_item_id,warehouse_id,variant_id,quantity,fulfilled_quantity) VALUES(920001,920001,920001,2,2)");
            jdbc.update("INSERT INTO inventory_movements(warehouse_id,variant_id,order_item_id,movement_type,quantity_delta,idempotency_key) VALUES(920001,920001,920001,'SHIPMENT',-2,'tv3-policy-movement')");
        });
    }
    @AfterEach void cleanup() {
        if(tx==null)return;
        tx.executeWithoutResult(status -> {
            for(String sql:List.of(
                "DELETE FROM refunds WHERE order_id=920001",
                "DELETE FROM payments WHERE order_id=920001",
                "DELETE FROM shipment_items WHERE order_id=920001",
                "DELETE FROM shipments WHERE order_id=920001",
                "DELETE FROM inventory_movements WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=920001)",
                "DELETE FROM inventory_reservations WHERE order_item_id IN (SELECT id FROM order_items WHERE order_id=920001)",
                "DELETE FROM order_items WHERE order_id=920001",
                "DELETE FROM orders WHERE id=920001",
                "DELETE FROM inventory_balances WHERE variant_id=920001",
                "DELETE FROM warehouses WHERE id=920001",
                "DELETE FROM product_variants WHERE id=920001",
                "DELETE FROM products WHERE id=920001",
                "DELETE FROM categories WHERE id=920001",
                "DELETE FROM users WHERE id=920001"))jdbc.update(sql);
        });
    }
    @Test void completePersistedEvidenceIsEligibleWithoutChangingLifecycleOrStock() {
        var result=policy.evaluate(ORDER);
        assertThat(result.eligible()).isTrue();assertThat(result.reasons()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id=920001",String.class)).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT on_hand_quantity FROM inventory_balances WHERE variant_id=920001",Integer.class)).isEqualTo(98);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_history WHERE order_id=920001",Integer.class)).isZero();
    }
    @Test void onlySucceededPaymentsCoverTheOrder() {
        jdbc.update("UPDATE payments SET amount=199.99 WHERE id=920001");
        for(String state:List.of("PENDING","FAILED","CANCELLED","EXPIRED")) {
            jdbc.update("INSERT INTO payments(order_id,method,provider,idempotency_key,amount,status) VALUES(920001,'COD','COD',?,100,?)","tv3-policy-"+state,state);
        }
        blocked("Thanh toán");
        jdbc.update("UPDATE payments SET amount=200 WHERE id=920001");eligible();
    }
    @Test void pendingAndSucceededRefundsReduceNetCollectionsWithoutJoinMultiplication() {
        jdbc.update("UPDATE payments SET amount=100 WHERE id=920001");
        jdbc.update("INSERT INTO payments(id,order_id,method,provider,idempotency_key,amount,status) VALUES(920002,920001,'COD','COD','tv3-policy-payment-2',100,'SUCCEEDED')");
        jdbc.update("UPDATE orders SET discount_amount=2,grand_total=198 WHERE id=920001");
        jdbc.update("INSERT INTO refunds(order_id,payment_id,idempotency_key,amount,status,reason) VALUES(920001,920001,'tv3-policy-refund-1',1,'PENDING','Policy refund'),(920001,920002,'tv3-policy-refund-2',1,'SUCCEEDED','Policy refund')");
        eligible();
        jdbc.update("UPDATE refunds SET amount=1.01 WHERE idempotency_key='tv3-policy-refund-1'");blocked("Thanh toán");
        jdbc.update("UPDATE refunds SET status='FAILED' WHERE idempotency_key='tv3-policy-refund-1'");eligible();
        jdbc.update("UPDATE refunds SET status='CANCELLED' WHERE idempotency_key='tv3-policy-refund-2'");eligible();
    }
    @Test void zeroTotalDoesNotRequirePaymentButStillRequiresFulfillment() {
        jdbc.update("DELETE FROM payments WHERE order_id=920001");
        jdbc.update("UPDATE orders SET discount_amount=200,grand_total=0 WHERE id=920001");eligible();
        jdbc.update("DELETE FROM inventory_movements WHERE order_item_id=920001");blocked("Biến động xuất kho");
    }
    @Test void missingOrderIsNotFoundAndMissingOrderLinesAreBlocked() {
        assertThatThrownBy(() -> policy.evaluate(ORDER+99)).isInstanceOfSatisfying(CommerceException.class,error -> assertThat(error.getStatus().value()).isEqualTo(404));
        jdbc.update("DELETE FROM shipment_items WHERE order_id=920001");
        jdbc.update("DELETE FROM inventory_reservations WHERE order_item_id=920001");
        jdbc.update("DELETE FROM inventory_movements WHERE order_item_id=920001");
        jdbc.update("DELETE FROM order_items WHERE order_id=920001");blocked("Đơn hàng chưa có sản phẩm");
    }
    @Test void everyActiveShipmentMustBeDeliveredWithTimestampAndAtLeastOneMustExist() {
        for(String state:List.of("PENDING","READY","IN_TRANSIT","FAILED","RETURNED")) {
            jdbc.update("UPDATE shipments SET status=? WHERE id=920001",state);blocked("Vận đơn");
        }
        jdbc.update("UPDATE shipments SET status='DELIVERED',delivered_at=NULL WHERE id=920001");blocked("Vận đơn");
        jdbc.update("UPDATE shipments SET status='CANCELLED',delivered_at=UTC_TIMESTAMP(6) WHERE id=920001");blocked("vận đơn");
        jdbc.update("DELETE FROM shipment_items WHERE order_id=920001");jdbc.update("DELETE FROM shipments WHERE order_id=920001");blocked("vận đơn");
    }
    @Test void deliveredQuantitiesMustEqualEachLineAndCancelledAllocationsDoNotCount() {
        for(int quantity:List.of(1,3)) {jdbc.update("UPDATE shipment_items SET quantity=? WHERE order_item_id=920001",quantity);blocked("Số lượng giao");}
        jdbc.update("UPDATE shipment_items SET quantity=2 WHERE order_item_id=920001");
        addShipment(920002,"CANCELLED",100,false);eligible();
    }
    @Test void reservationsMustBeExactlyFulfilledAndHaveNoRemainingHolds() {
        jdbc.update("UPDATE inventory_reservations SET fulfilled_quantity=1 WHERE order_item_id=920001");blocked("Số lượng xuất kho");blocked("giữ kho");
        jdbc.update("UPDATE inventory_reservations SET quantity=3,fulfilled_quantity=2 WHERE order_item_id=920001");blocked("giữ kho");
        jdbc.update("UPDATE inventory_reservations SET released_quantity=1 WHERE order_item_id=920001");eligible();
        jdbc.update("UPDATE inventory_reservations SET released_quantity=0,fulfilled_quantity=3 WHERE order_item_id=920001");blocked("Số lượng xuất kho");
    }
    @Test void shipmentMovementNetMustExactlyMatchEveryOrderedLine() {
        for(int quantity:List.of(-1,-3)) {jdbc.update("UPDATE inventory_movements SET quantity_delta=? WHERE order_item_id=920001",quantity);blocked("Biến động xuất kho");}
        jdbc.update("UPDATE inventory_movements SET movement_type='RETURN',quantity_delta=2 WHERE order_item_id=920001");blocked("Biến động xuất kho");
        jdbc.update("UPDATE inventory_movements SET movement_type='SHIPMENT',quantity_delta=-1 WHERE order_item_id=920001");
        jdbc.update("INSERT INTO inventory_movements(warehouse_id,variant_id,order_item_id,movement_type,quantity_delta,idempotency_key) VALUES(920001,920001,920001,'SHIPMENT',-1,'tv3-policy-movement-2')");eligible();
    }
    @Test void selectedFreeAssemblyRequiresAllInstallationEvidence() {
        jdbc.update("UPDATE orders SET assembly_requested=1 WHERE id=920001");blocked("lắp đặt");
        completeInstallation(SHIPMENT);eligible();
        jdbc.update("UPDATE shipments SET installation_staff_id=NULL WHERE id=920001");blocked("lắp đặt");
        completeInstallation(SHIPMENT);jdbc.update("UPDATE shipments SET installation_completed_at=NULL WHERE id=920001");blocked("lắp đặt");
        completeInstallation(SHIPMENT);jdbc.update("UPDATE shipments SET installation_status='PENDING' WHERE id=920001");blocked("lắp đặt");
        assertThat(jdbc.queryForObject("SELECT assembly_fee FROM orders WHERE id=920001",java.math.BigDecimal.class)).isZero();
    }
    @Test void unselectedOrTechnicallyUnnecessaryAssemblyDoesNotNeedInstaller() {
        eligible();
        jdbc.update("UPDATE orders SET assembly_requested=1 WHERE id=920001");
        jdbc.update("UPDATE order_items SET assembly_required=0 WHERE id=920001");eligible();
    }
    @Test void eachDeliveredShipmentContainingAssemblyRequiredLinesNeedsProof() {
        jdbc.update("UPDATE orders SET assembly_requested=1 WHERE id=920001");
        jdbc.update("UPDATE shipment_items SET quantity=1 WHERE order_item_id=920001");
        completeInstallation(SHIPMENT);addShipment(920002,"DELIVERED",1,false);blocked("920002");
        completeInstallation(920002);eligible();
        addShipment(920003,"CANCELLED",1,false);eligible();
    }
    @Test void readsAreBatchedAndEachEvaluationUsesFreshEvidence() {
        dataSource.reads.set(0);eligible();int oneLineReads=dataSource.reads.get();
        jdbc.update("UPDATE payments SET status='PENDING' WHERE id=920001");blocked("Thanh toán");
        for(int i=1;i<=20;i++)jdbc.update("INSERT INTO order_items(id,order_id,product_id,variant_id,product_name,variant_name,sku,quantity,unit_price) VALUES(?,920001,920001,920001,'Policy Sofa','Oak','TV3-POLICY-SKU',1,100)",LINE+i);
        dataSource.reads.set(0);assertThat(policy.evaluate(ORDER).eligible()).isFalse();
        assertThat(dataSource.reads.get()).isEqualTo(oneLineReads);
        assertThat(oneLineReads).isBetween(1,8);
    }
    private void addShipment(long id,String status,int quantity,boolean installed) {
        jdbc.update("INSERT INTO shipments(id,order_id,warehouse_id,status,delivered_at) VALUES(?,920001,920001,?,UTC_TIMESTAMP(6))",id,status);
        jdbc.update("INSERT INTO shipment_items(shipment_id,order_id,order_item_id,quantity) VALUES(?,920001,920001,?)",id,quantity);
        if(installed)completeInstallation(id);
    }
    private void completeInstallation(long shipment) {
        jdbc.update("UPDATE shipments SET installation_required=1,installation_status='COMPLETED',installation_completed_at=UTC_TIMESTAMP(6),installation_staff_id=920001 WHERE id=?",shipment);
    }
    private void eligible() {var result=policy.evaluate(ORDER);assertThat(result.eligible()).describedAs(result.reasons().toString()).isTrue();assertThat(result.reasons()).isEmpty();}
    private void blocked(String reasonFragment) {var result=policy.evaluate(ORDER);assertThat(result.eligible()).isFalse();assertThat(result.reasons()).anyMatch(reason -> reason.contains(reasonFragment));}

    /** Counts actual SQL reads at the JDBC boundary, independent of policy query overloads. */
    static final class CountingDataSource extends AbstractDataSource {
        private final DataSource delegate;final AtomicInteger reads=new AtomicInteger();
        CountingDataSource(DataSource delegate) {this.delegate=delegate;}
        @Override public Connection getConnection() throws SQLException {return wrap(delegate.getConnection());}
        @Override public Connection getConnection(String user,String password) throws SQLException {return wrap(delegate.getConnection(user,password));}
        private Connection wrap(Connection actual) {
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args) -> {
                try {
                    Object result=method.invoke(actual,args);
                    if(method.getName().equals("prepareStatement") && args!=null && args[0] instanceof String sql)count(sql);
                    if(method.getName().equals("createStatement") && result instanceof Statement statement)return Proxy.newProxyInstance(Statement.class.getClassLoader(),new Class<?>[]{Statement.class},(p,m,a) -> {
                        try {if(m.getName().equals("executeQuery") && a!=null && a[0] instanceof String sql)count(sql);return m.invoke(statement,a);}
                        catch(InvocationTargetException exception){throw exception.getCause();}
                    });
                    return result;
                }catch(InvocationTargetException exception){throw exception.getCause();}
            });
        }
        private void count(String sql) {if(sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT"))reads.incrementAndGet();}
    }
}
