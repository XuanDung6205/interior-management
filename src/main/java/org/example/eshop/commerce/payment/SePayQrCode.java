package org.example.eshop.commerce.payment;

import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;

/** VietQR account transfer payload + PNG, entirely offline; no outbound HTTP. */
public final class SePayQrCode {
    private SePayQrCode(){}
    static String payload(String account,BigDecimal amount,String content){
        if(account==null||!account.matches("[0-9]{6,19}")||content==null||!content.matches("[A-Za-z0-9 ]{1,25}")||amount.signum()<=0||amount.stripTrailingZeros().scale()>0)throw new IllegalArgumentException("Invalid local VietQR facts");
        String beneficiary=field("00","970415")+field("01",account);
        String merchant=field("00","A000000727")+field("01",beneficiary)+field("02","QRIBFTTA");
        String body=field("00","01")+field("01","12")+field("38",merchant)+field("53","704")
                +field("54",amount.toBigIntegerExact().toString())+field("58","VN")+field("62",field("08",content))+"6304";
        int crc=0xFFFF;
        for(byte b:body.getBytes(StandardCharsets.US_ASCII)){
            crc^=(b&255)<<8;for(int i=0;i<8;i++)crc=(crc&0x8000)!=0?(crc<<1)^0x1021:crc<<1;crc&=0xFFFF;
        }
        return body+String.format(Locale.ROOT,"%04X",crc);
    }
    private static String field(String id,String value){if(value.length()>99)throw new IllegalArgumentException("VietQR field too long");return id+String.format(Locale.ROOT,"%02d",value.length())+value;}
    public static byte[] png(String account,BigDecimal amount,String content){
        try{
            var matrix=new QRCodeWriter().encode(payload(account,amount,content),BarcodeFormat.QR_CODE,320,320,
                    Map.of(EncodeHintType.MARGIN,4,EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M));
            var image=new BufferedImage(matrix.getWidth(),matrix.getHeight(),BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<matrix.getHeight();y++)for(int x=0;x<matrix.getWidth();x++)image.setRGB(x,y,matrix.get(x,y)?0x000000:0xFFFFFF);
            var bytes=new ByteArrayOutputStream();if(!ImageIO.write(image,"PNG",bytes))throw new IllegalStateException("PNG encoder unavailable");return bytes.toByteArray();
        }catch(WriterException|java.io.IOException e){throw new IllegalStateException("Local QR generation failed",e);}
    }
}
