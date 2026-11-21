package org.example.eshop.commerce.order;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** Human-readable spreadsheet export; quoting and text protection are separate. */
final class OrderCsvWriter {
    private static final List<String> HEADERS = List.of("Mã đơn", "Ngày đặt (UTC+07:00)", "Trạng thái", "Người nhận", "Điện thoại",
            "Tiền hàng", "Giảm giá", "Phí giao hàng", "Phí lắp đặt", "Thuế bổ sung", "Tổng đơn", "Tiền tệ", "Đã thu", "Đã hoàn");
    private static final List<String> FIELDS = List.of("orderNumber", "placedAt", "status", "recipientName", "recipientPhone",
            "subtotal", "discountAmount", "shippingFee", "assemblyFee", "taxAmount", "grandTotal", "currency", "collectedAmount", "refundedAmount");

    static byte[] write(List<Map<String,Object>> rows) {
        var csv = new StringBuilder("\uFEFF");
        line(csv, HEADERS);
        for (var row : rows) line(csv, FIELDS.stream().map(field -> Objects.toString(row.get(field), "")).toList());
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void line(StringBuilder csv, List<String> cells) {
        for (int i=0; i<cells.size(); i++) {
            if (i>0) csv.append(',');
            csv.append('"').append(protect(cells.get(i)).replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
    private static String protect(String text) {
        boolean controlStart = !text.isEmpty() && (text.charAt(0)=='\t' || text.charAt(0)=='\r' || text.charAt(0)=='\n');
        int offset=0;
        while (offset<text.length()) {
            int c=text.codePointAt(offset);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c) && !Character.isISOControl(c) && Character.getType(c)!=Character.FORMAT) break;
            offset+=Character.charCount(c);
        }
        String normalized=Normalizer.normalize(text.substring(offset), Normalizer.Form.NFKC);
        boolean formula=!normalized.isEmpty() && "=+-@".indexOf(normalized.charAt(0))>=0;
        // A leading tab inside the quoted field is a spreadsheet-display defense.
        // Do not reuse this export as a lossless machine-import format.
        return formula || controlStart ? "\t"+text : text;
    }
}
