package org.example.eshop.commerce.payment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;

class SePayQrCodeTest {
    @Test void matchesPublishedVietQrPayloadIncludingCrc(){
        // Public vendor documentation example; independent of our payload builder.
        assertEquals("00020101021238560010A0000007270126000697041501121133666688880208QRIBFTTA53037045405790005802VN62220818Ung Ho Quy Vac Xin63043ACF",SePayQrCode.payload("113366668888",new BigDecimal("79000"),"Ung Ho Quy Vac Xin"));
    }
    @Test void generatedPngDecodesToTheActualTransferFacts()throws Exception{
        var bytes=SePayQrCode.png("0000000001",new BigDecimal("2540000.00"),"SEVQR DH00000009");
        var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));int w=image.getWidth(),h=image.getHeight();
        var source=new com.google.zxing.RGBLuminanceSource(w,h,image.getRGB(0,0,w,h,null,0,w));
        String decoded=new com.google.zxing.MultiFormatReader().decode(new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(source))).getText();
        assertTrue(decoded.contains("000697041501100000000001"));assertTrue(decoded.contains("54072540000"));assertTrue(decoded.contains("SEVQR DH00000009"));assertTrue(decoded.endsWith(String.format("%04X",crc(decoded.substring(0,decoded.length()-4)))));
    }
    private int crc(String text){int sum=0xFFFF;for(byte b:text.getBytes(java.nio.charset.StandardCharsets.US_ASCII)){sum^=(b&255)<<8;for(int i=0;i<8;i++)sum=(sum&0x8000)!=0?(sum<<1)^0x1021:sum<<1;sum&=0xFFFF;}return sum;}
}
