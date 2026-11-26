package org.example.eshop.commerce.integration;

import org.example.eshop.commerce.port.CheckoutAddressPort;
import org.example.eshop.commerce.port.CheckoutCatalogPort;
import org.example.eshop.commerce.port.InventoryPort;
import org.example.eshop.commerce.cache.Tv5Cache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named="TV3_TEST_DB_URL",matches=".*furniture_shop_test_.*")
class CheckoutAdaptersTest extends CheckoutAdapterFixture {
    @Test void catalogBatchesCompleteSnapshotsAndRetainsInactiveProducts() {
        var products=catalog.findProducts(Set.of(VARIANT,VARIANT+1));
        var sofa=products.get(VARIANT);
        assertThat(sofa.sku()).isEqualTo("TV3-ADAPTER-OAK");
        assertThat(sofa.productName()).isEqualTo("Adapter Sofa");
        assertThat(sofa.variantName()).isEqualTo("Oak");
        assertThat(sofa.colorName()).isEqualTo("Natural");
        assertThat(sofa.dimensionsSnapshot()).isEqualTo("W 1200.00 mm × D 800.00 mm × H 900.00 mm");
        assertThat(sofa.materialsSnapshot()).isEqualTo("FRAME: Oak; UPHOLSTERY: Cotton");
        assertThat(sofa.warrantyMonths()).isEqualTo(24);
        assertThat(sofa.assemblyRequired()).isTrue();
        assertThat(sofa.unitPrice()).isEqualByComparingTo(new BigDecimal("100.05"));
        assertThat(sofa.imageUrl()).isEqualTo("/variant.jpg");
        assertThat(sofa.active()).isTrue();
        assertThat(products.get(VARIANT+1).active()).isFalse();
        assertThat(products.get(VARIANT+1).imageUrl()).isEqualTo("/product.jpg");
        assertThat(catalog.findProducts(Set.of())).isEmpty();
    }
    @Test void addressesAreOwnedAndUseOnlyFieldsAvailableInAddressBook() {
        var own=addresses.findByUserId(USER);
        assertThat(own).hasSize(1);
        assertThat(own.get(0).id()).isEqualTo(ADDRESS);
        assertThat(own.get(0).recipientName()).isEqualTo("Adapter Customer");
        assertThat(own.get(0).email()).isEqualTo("adapter@example.invalid");
        assertThat(own.get(0).countryCode()).isEqualTo("VN");
        assertThat(own.get(0).companyName()).isNull();
        assertThat(own.get(0).taxCode()).isNull();
        assertThat(addresses.findOwned(USER,ADDRESS)).isPresent();
        assertThat(addresses.findOwned(USER,ADDRESS+1)).isEmpty();
        assertThat(addresses.findByUserId(USER+99)).isEmpty();
    }
}

@SpringJUnitConfig(CheckoutAdapterFixture.Config.class)
abstract class CheckoutAdapterFixture {
    static final long USER=910001,ADDRESS=910001,PRODUCT=910001,VARIANT=910001,WAREHOUSE=910001,ORDER=910001,ITEM=910001;
    @TestConfiguration @EnableTransactionManagement
    @Import({JdbcInventoryAdapter.class,JdbcCheckoutCatalogAdapter.class,JdbcCheckoutAddressAdapter.class,Tv5Cache.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("TV3_TEST_DB_URL"),System.getenv().getOrDefault("DB_USERNAME","root"),System.getenv("DB_PASSWORD"));
        }
        @Bean NamedParameterJdbcTemplate namedJdbc(DataSource source) { return new NamedParameterJdbcTemplate(source); }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean Clock clock() { return Clock.systemUTC(); }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired CheckoutCatalogPort catalog;
    @Autowired CheckoutAddressPort addresses;
    @Autowired InventoryPort inventory;
    TransactionTemplate tx;
    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).startsWith("furniture_shop_test_");
        tx=new TransactionTemplate(transactions);
        cleanup();
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO users(id,username,email,password_hash,full_name) VALUES(910001,'tv3-adapter','adapter@example.invalid','not-a-login-hash','Adapter Customer'),(910002,'tv3-adapter-other','adapter-other@example.invalid','not-a-login-hash','Other Customer')");
            jdbc.update("INSERT INTO user_addresses(id,user_id,label,recipient_name,phone,address_line1,district_name,province_name) VALUES(910001,910001,'Home','Adapter Customer','0901234567','10 Test Street','Test District','Test Province'),(910002,910002,'Other','Other Customer','0901234568','20 Test Street','Other District','Other Province')");
            jdbc.update("INSERT INTO categories(id,name,slug) VALUES(910001,'Adapter Sofa','tv3-adapter-sofa')");
            jdbc.update("INSERT INTO products(id,category_id,name,slug,status,warranty_months,assembly_required) VALUES(910001,910001,'Adapter Sofa','tv3-adapter-sofa','ACTIVE',24,1)");
            jdbc.update("INSERT INTO product_variants(id,product_id,sku,variant_key,name,color_name,width_mm,depth_mm,height_mm,price,status) VALUES(910001,910001,'TV3-ADAPTER-OAK','oak','Oak','Natural',1200,800,900,100.05,'ACTIVE'),(910002,910001,'TV3-ADAPTER-OLD','old','Old',NULL,NULL,NULL,NULL,200,'INACTIVE')");
            jdbc.update("INSERT INTO product_images(product_id,variant_id,url,sort_order) VALUES(910001,NULL,'/product.jpg',0),(910001,910001,'/variant.jpg',10)");
            jdbc.update("INSERT INTO materials(id,name,slug) VALUES(910001,'Oak','tv3-adapter-oak'),(910002,'Cotton','tv3-adapter-cotton')");
            jdbc.update("INSERT INTO variant_materials(variant_id,material_id,component_name) VALUES(910001,910001,'FRAME'),(910001,910002,'UPHOLSTERY')");
            jdbc.update("INSERT INTO warehouses(id,code,name,is_active) VALUES(910001,'TV3-ADAPTER-A','Adapter A',1),(910002,'TV3-ADAPTER-B','Adapter B',1),(910003,'TV3-ADAPTER-INACTIVE','Adapter inactive',0)");
            jdbc.update("INSERT INTO inventory_balances(warehouse_id,variant_id,on_hand_quantity,reserved_quantity) VALUES(910001,910001,5,2),(910002,910001,6,0),(910003,910001,100,0)");
            jdbc.update("INSERT INTO orders(id,order_number,user_id,idempotency_key,subtotal,grand_total) VALUES(910001,'TV3-ADAPTER-ORDER',910001,'tv3-adapter-key',1000.50,1000.50),(910002,'TV3-ADAPTER-ORDER-2',910001,'tv3-adapter-key-2',1000.50,1000.50)");
            jdbc.update("INSERT INTO order_items(id,order_id,product_id,variant_id,product_name,variant_name,sku,quantity,unit_price) VALUES(910001,910001,910001,910001,'Adapter Sofa','Oak','TV3-ADAPTER-OAK',10,100.05),(910002,910002,910001,910001,'Adapter Sofa','Oak','TV3-ADAPTER-OAK',10,100.05)");
        });
    }
    @AfterEach void cleanup() {
        if(tx==null) return;
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM shipment_items WHERE order_id IN (910001,910002)");
            jdbc.update("DELETE FROM shipments WHERE order_id IN (910001,910002)");
            jdbc.update("DELETE FROM inventory_movements WHERE order_item_id IN (910001,910002)");
            jdbc.update("DELETE FROM inventory_reservations WHERE order_item_id IN (910001,910002)");
            jdbc.update("DELETE FROM order_items WHERE order_id IN (910001,910002)");
            jdbc.update("DELETE FROM orders WHERE id IN (910001,910002)");
            jdbc.update("DELETE FROM inventory_balances WHERE variant_id IN (910001,910002)");
            jdbc.update("DELETE FROM warehouses WHERE id IN (910001,910002,910003)");
            jdbc.update("DELETE FROM variant_materials WHERE variant_id IN (910001,910002)");
            jdbc.update("DELETE FROM product_images WHERE product_id=910001");
            jdbc.update("DELETE FROM product_variants WHERE product_id=910001");
            jdbc.update("DELETE FROM products WHERE id=910001");
            jdbc.update("DELETE FROM materials WHERE id IN (910001,910002)");
            jdbc.update("DELETE FROM categories WHERE id=910001");
            jdbc.update("DELETE FROM user_addresses WHERE user_id IN (910001,910002)");
            jdbc.update("DELETE FROM users WHERE id IN (910001,910002)");
        });
    }
}
