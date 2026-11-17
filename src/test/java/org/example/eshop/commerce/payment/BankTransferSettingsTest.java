package org.example.eshop.commerce.payment;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BankTransferSettingsTest {
    private JdbcTemplate database(String name) {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT DATABASE()",String.class)).thenReturn(name);
        return jdbc;
    }
    private BankTransferSettings manual(String bank,String account,String holder) {
        return new BankTransferSettings(mock(JdbcTemplate.class),"MANUAL",bank,account,holder);
    }
    @Test void unconfiguredShowsClearNoticeWithoutPublishingAccountInformation() {
        var settings=new BankTransferSettings(mock(JdbcTemplate.class),"UNCONFIGURED","Not enabled","000123456789","Not enabled");
        var result=settings.forOrder("NN-ORDER-001");
        assertThat(result).containsEntry("mode","UNCONFIGURED").containsEntry("configured",false).containsEntry("isDemo",false)
            .containsEntry("bankName",null).containsEntry("accountNumber",null).containsEntry("accountHolder",null)
            .containsEntry("transferContent","NN-ORDER-001");
        assertThat(result.get("notice").toString()).containsIgnoringCase("chưa cấu hình").containsIgnoringCase("không chuyển tiền");
        assertThat(result).hasSize(8);
    }
    @Test void demoIsConspicuouslyInvalidAndAllowedOnlyInIsolatedSchemas() {
        for(String schema:new String[]{"furniture_shop_tv3_dev","furniture_shop_test_bank_20261117"}) {
            var settings=new BankTransferSettings(database(schema),"DEMO","Supplied real bank","000123456789","Supplied real holder");
            var result=settings.forOrder("NN-DEMO-001");
            assertThat(result).containsEntry("mode","DEMO").containsEntry("configured",true).containsEntry("isDemo",true)
                .containsEntry("accountNumber","DEMO-KHONG-CHUYEN-TIEN").containsEntry("transferContent","NN-DEMO-001");
            assertThat(result.get("notice").toString()).containsIgnoringCase("không phải").containsIgnoringCase("sandbox").containsIgnoringCase("không chuyển tiền thật");
            assertThat(result.get("bankName")).isNotEqualTo("Supplied real bank");
            assertThat(result.get("accountHolder")).isNotEqualTo("Supplied real holder");
            assertThat(result).hasSize(8);
            assertThat(result.values()).noneMatch(value -> value instanceof String text && text.contains("http"));
        }
    }
    @Test void demoFailsFastForProductionUnknownAndLookalikeDatabaseNames() {
        for(String schema:new String[]{"furniture_shop","furniture_shop_test","other_furniture_shop_test_bank","furniture_shop_tv3_dev_live",null}) {
            assertThatThrownBy(() -> new BankTransferSettings(database(schema),"DEMO","","",""))
                .describedAs("DEMO schema %s must be refused",schema).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void manualPreservesTrimmedPublicInstructionsAndLeadingZeroes() {
        var settings=new BankTransferSettings(mock(JdbcTemplate.class)," manual ","  Ngân hàng kiểm thử  ","  000123456789  ","  CHU TAI KHOAN KIEM THU  ");
        Map<String,Object> result=settings.forOrder("NN-20261117-ABC_123");
        assertThat(result).containsEntry("mode","MANUAL").containsEntry("configured",true).containsEntry("isDemo",false)
            .containsEntry("bankName","Ngân hàng kiểm thử").containsEntry("accountNumber","000123456789")
            .containsEntry("accountHolder","CHU TAI KHOAN KIEM THU").containsEntry("transferContent","NN-20261117-ABC_123");
        assertThat(result.get("notice").toString()).isNotBlank();
        assertThat(result).hasSize(8);
    }
    @Test void manualRequiresAllThreePayeeFields() {
        for(String missing:new String[]{null,"","   "}) {
            assertThatThrownBy(() -> manual(missing,"000123456789","Holder")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> manual("Bank",missing,"Holder")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> manual("Bank","000123456789",missing)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void manualAcceptsFieldLengthBoundariesAndRejectsLongerInstructions() {
        assertThat(manual("B".repeat(100),"0".repeat(64),"H".repeat(150)).forOrder("NN-BOUNDARY").get("configured")).isEqualTo(true);
        assertThatThrownBy(() -> manual("B".repeat(101),"000123456789","Holder")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> manual("Bank","0".repeat(65),"Holder")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> manual("Bank","000123456789","H".repeat(151))).isInstanceOf(IllegalStateException.class);
    }
    @Test void accountFormatRequiresSixToSixtyFourAsciiDigitsWithoutClaimingVerification() {
        assertThat(manual("Bank","000001","Holder").forOrder("NN-ACCOUNT").get("accountNumber")).isEqualTo("000001");
        for(String malformed:new String[]{"12345","12345A","123-456","123 456","１２３４５６","DEMO-KHONG-CHUYEN-TIEN"}) {
            assertThatThrownBy(() -> manual("Bank",malformed,"Holder")).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void controlsAreRejectedBeforeWhitespaceTrimming() {
        for(String control:new String[]{"\n","\r","\t","\u0000","\u007f","\u202e"}) {
            assertThatThrownBy(() -> manual(control+"Bank","000123456789","Holder")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> manual("Bank",control+"000123456789","Holder")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> manual("Bank","000123456789",control+"Holder")).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void unknownOrControlContainingModesFailFast() {
        for(String mode:new String[]{"","SANDBOX","PRODUCTION","DEMO\n",null}) {
            assertThatThrownBy(() -> new BankTransferSettings(database("furniture_shop_test_bank"),mode,"Bank","000123456789","Holder"))
                .isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void transferContentAlwaysUsesTheExactOrderNumberWithoutExternalQrOrAccountDerivation() {
        var settings=manual("Bank","000123456789","Holder");
        assertThat(settings.forOrder("NN-ĐƠN_HÀNG-001")).containsEntry("transferContent","NN-ĐƠN_HÀNG-001");
        assertThat(settings.forOrder("NN-OTHER-002")).containsEntry("transferContent","NN-OTHER-002").containsEntry("accountNumber","000123456789");
    }
}
