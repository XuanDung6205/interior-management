package org.example.eshop.commerce.ai;

import org.example.eshop.commerce.common.CommerceException;
import org.example.eshop.commerce.content.ContentValidation;
import org.example.eshop.commerce.port.CommerceActor;
import org.example.eshop.commerce.port.CurrentActorPort;
import org.example.eshop.commerce.vector.CatalogVectorSearch;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Trợ lý tư vấn nội thất RAG: lưu hội thoại có xác thực chủ sở hữu, lấy giá/tồn thật từ DB và có fallback khi thiếu khóa AI
@Service
public class FurnitureChatService {
    private static final Logger log = LoggerFactory.getLogger(FurnitureChatService.class);
    private static final int MAX_MESSAGE_LENGTH = 1000;
    private static final int MAX_HISTORY_MESSAGES = 8;
    private static final int MAX_REQUESTS_PER_MINUTE = 12;
    private static final Pattern BUDGET_MILLION = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:triệu|trieu|tr\\b)", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbc;
    private final CurrentActorPort actors;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final RestClient httpClient;
    private final String geminiApiKey;
    private final String geminiModel;
    private CatalogVectorSearch vectorSearch;

    @Autowired
    public void setCatalogVectorSearch(CatalogVectorSearch vectorSearch) { this.vectorSearch = vectorSearch; }

    // Giới hạn tốc độ gọi chat theo từng tài khoản/khách để tránh lạm dụng ngân sách AI
    private final ConcurrentHashMap<String, Deque<Long>> rateWindows = new ConcurrentHashMap<>();

    public FurnitureChatService(
            JdbcTemplate jdbc,
            CurrentActorPort actors,
            ObjectMapper mapper,
            Clock clock,
            @Value("${spring.ai.gemini.api-key:}") String geminiApiKey,
            @Value("${spring.ai.gemini.model:gemini-2.0-flash}") String geminiModel) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.mapper = mapper;
        this.clock = clock;
        this.geminiApiKey = geminiApiKey == null ? "" : geminiApiKey.trim();
        this.geminiModel = (geminiModel == null || geminiModel.isBlank()) ? "gemini-2.0-flash" : geminiModel.trim();

        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5_000);
        requestFactory.setReadTimeout(25_000);
        this.httpClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public record ChatSource(
            long productId, String productName, String sku, String variantName,
            String categoryName, String price, int availableQuantity, String imageUrl) {}

    public record ChatMessageView(
            long id, int sequenceNumber, String role, String content,
            String model, List<ChatSource> sources, String createdAt) {}

    public record ConversationSummary(String publicId, String title, String status, String updatedAt) {}

    public record ConversationDetail(
            String publicId, String title, String status,
            boolean aiConfigured, List<ChatMessageView> messages) {}

    public record SendMessageRequest(String conversationId, String message) {}

    public record SendMessageResponse(
            String conversationId, boolean aiConfigured, String model,
            String reply, List<ChatSource> sources, String createdAt) {}

    // Liệt kê các cuộc trò chuyện thuộc quyền sở hữu của user hoặc guest hiện tại
    @Transactional(readOnly = true)
    public List<ConversationSummary> listMine() {
        CommerceActor actor = actors.currentActor();
        if (actor.userId() != null) {
            return jdbc.query("""
                    SELECT public_id, title, status, updated_at
                    FROM chat_conversations
                    WHERE user_id = ? AND status <> 'ARCHIVED'
                    ORDER BY updated_at DESC, id DESC
                    LIMIT 20
                    """, (rs, row) -> new ConversationSummary(
                    rs.getString("public_id"), rs.getString("title"),
                    rs.getString("status"), toIso(rs.getTimestamp("updated_at"))), actor.userId());
        }
        if (actor.guestHash() != null) {
            return jdbc.query("""
                    SELECT public_id, title, status, updated_at
                    FROM chat_conversations
                    WHERE guest_token_hash = ? AND status <> 'ARCHIVED'
                    ORDER BY updated_at DESC, id DESC
                    LIMIT 20
                    """, (rs, row) -> new ConversationSummary(
                    rs.getString("public_id"), rs.getString("title"),
                    rs.getString("status"), toIso(rs.getTimestamp("updated_at"))), (Object) actor.guestHash());
        }
        return List.of();
    }

    // Đọc lịch sử tin nhắn của 1 hội thoại (bắt buộc xác thực đúng chủ hội thoại, không chỉ dựa vào publicId)
    @Transactional(readOnly = true)
    public ConversationDetail getConversation(String publicId) {
        CommerceActor actor = actors.currentActor();
        ConversationRow conv = findOwnedConversation(publicId, actor);
        var messages = loadMessages(conv.id());
        return new ConversationDetail(conv.publicId(), conv.title(), conv.status(), isAiConfigured(), messages);
    }

    // Gửi câu hỏi tư vấn, lưu tin nhắn USER + ASSISTANT vào DB và trả lời dựa trên giá/tồn hiện hành
    public SendMessageResponse sendMessage(SendMessageRequest req) {
        CommerceActor actor = actors.currentActor();
        if (actor.userId() == null && actor.guestHash() == null) {
            throw CommerceException.forbidden("Không xác định được phiên trò chuyện.");
        }
        String text = req == null ? null : ContentValidation.trimToNull(req.message());
        if (text == null) {
            throw CommerceException.invalid(Map.of("message", "Vui lòng nhập câu hỏi cần tư vấn."));
        }
        if (text.length() > MAX_MESSAGE_LENGTH) {
            throw CommerceException.invalid(Map.of("message", "Tin nhắn tối đa " + MAX_MESSAGE_LENGTH + " ký tự."));
        }

        enforceRateLimit(actor);

        // Bước 1: mở hoặc lấy hội thoại thuộc chủ sở hữu và lưu tin nhắn USER trước khi gọi mạng ra ngoài
        ConversationRow conv = openOrLoadConversation(req.conversationId(), text, actor);
        List<ChatMessageView> history = loadRecentHistory(conv.id());
        appendMessage(conv.id(), "USER", text, null, List.of());

        // Bước 2: truy xuất sản phẩm nội thất thật kèm giá và tồn kho hiện tại từ MariaDB
        List<ChatSource> sources = retrieveLiveCatalog(text);

        // Bước 3: gọi Gemini ngoài transaction DB (nếu chưa có khóa hoặc gọi lỗi thì trả fallback minh bạch)
        AiOutcome outcome = generateReply(text, history, sources);
        String createdAt = appendMessage(conv.id(), "ASSISTANT", outcome.reply(), outcome.model(), sources);

        return new SendMessageResponse(
                conv.publicId(),
                isAiConfigured(),
                outcome.model(),
                outcome.reply(),
                sources,
                createdAt);
    }

    public boolean isAiConfigured() {
        return !geminiApiKey.isEmpty();
    }

    // Kiểm tra giới hạn số tin nhắn mỗi phút theo từng userId hoặc guestHash
    private void enforceRateLimit(CommerceActor actor) {
        String bucketKey = actor.userId() != null
                ? "u:" + actor.userId()
                : "g:" + Base64.getEncoder().encodeToString(actor.guestHash());
        long nowMs = clock.millis();
        long cutoff = nowMs - 60_000L;
        Deque<Long> timestamps = rateWindows.computeIfAbsent(bucketKey, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= MAX_REQUESTS_PER_MINUTE) {
                throw CommerceException.conflict("CHAT_RATE_LIMITED",
                        "Bạn đang gửi tin nhắn quá nhanh. Vui lòng chờ khoảng 1 phút rồi thử lại.");
            }
            timestamps.addLast(nowMs);
        }
    }

    private ConversationRow openOrLoadConversation(String publicId, String firstMessage, CommerceActor actor) {
        String idText = ContentValidation.trimToNull(publicId);
        if (idText != null) {
            return findOwnedConversation(idText, actor);
        }
        String newPublicId = UUID.randomUUID().toString();
        String title = firstMessage.length() > 80 ? firstMessage.substring(0, 80) + "…" : firstMessage;
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        // Tuân thủ ràng buộc ck_chat_owner: chỉ 1 trong 2 cột user_id hoặc guest_token_hash khác NULL
        Long userId = actor.userId();
        byte[] guestHash = userId == null ? actor.guestHash() : null;

        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            var ps = conn.prepareStatement("""
                    INSERT INTO chat_conversations(public_id, user_id, guest_token_hash, title, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'OPEN', ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, newPublicId);
            ps.setObject(2, userId);
            ps.setBytes(3, guestHash);
            ps.setString(4, title);
            ps.setObject(5, now);
            ps.setObject(6, now);
            return ps;
        }, keyHolder);

        return new ConversationRow(keyHolder.getKey().longValue(), newPublicId, title, "OPEN");
    }

    // Luôn kiểm tra cả public_id lẫn chủ sở hữu để người khác không đọc trộm được dù biết UUID
    private ConversationRow findOwnedConversation(String publicId, CommerceActor actor) {
        String cleanId = ContentValidation.trimToNull(publicId);
        if (cleanId == null || cleanId.length() > 36) {
            throw CommerceException.notFound("Không tìm thấy hội thoại.");
        }
        List<ConversationRow> rows;
        if (actor.userId() != null) {
            rows = jdbc.query("""
                    SELECT id, public_id, title, status
                    FROM chat_conversations
                    WHERE public_id = ? AND user_id = ?
                    """, (rs, r) -> new ConversationRow(
                    rs.getLong("id"), rs.getString("public_id"), rs.getString("title"), rs.getString("status")),
                    cleanId, actor.userId());
        } else if (actor.guestHash() != null) {
            rows = jdbc.query("""
                    SELECT id, public_id, title, status
                    FROM chat_conversations
                    WHERE public_id = ? AND guest_token_hash = ?
                    """, (rs, r) -> new ConversationRow(
                    rs.getLong("id"), rs.getString("public_id"), rs.getString("title"), rs.getString("status")),
                    cleanId, actor.guestHash());
        } else {
            rows = List.of();
        }
        if (rows.isEmpty()) {
            throw CommerceException.notFound("Không tìm thấy hội thoại hoặc bạn không có quyền truy cập.");
        }
        return rows.get(0);
    }

    private List<ChatMessageView> loadMessages(long conversationId) {
        return jdbc.query("""
                SELECT id, sequence_number, role, content, model, sources, created_at
                FROM chat_messages
                WHERE conversation_id = ?
                ORDER BY sequence_number ASC
                """, (rs, r) -> new ChatMessageView(
                rs.getLong("id"),
                rs.getInt("sequence_number"),
                rs.getString("role"),
                rs.getString("content"),
                rs.getString("model"),
                parseSources(rs.getString("sources")),
                toIso(rs.getTimestamp("created_at"))), conversationId);
    }

    private List<ChatMessageView> loadRecentHistory(long conversationId) {
        var all = loadMessages(conversationId);
        if (all.size() <= MAX_HISTORY_MESSAGES) {
            return all;
        }
        return all.subList(all.size() - MAX_HISTORY_MESSAGES, all.size());
    }

    private String appendMessage(long conversationId, String role, String content, String model, List<ChatSource> sources) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Integer nextSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM chat_messages WHERE conversation_id = ?",
                Integer.class, conversationId);
        int seq = nextSeq == null ? 1 : nextSeq;
        String sourcesJson = sources.isEmpty() ? null : serializeSources(sources);

        jdbc.update("""
                INSERT INTO chat_messages(conversation_id, sequence_number, role, content, model, sources, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, conversationId, seq, role, content, model, sourcesJson, now);
        jdbc.update("UPDATE chat_conversations SET updated_at = ? WHERE id = ?", now, conversationId);
        return now.atOffset(ZoneOffset.UTC).toString();
    }

    // Truy vấn trực tiếp catalog và tồn kho hiện tại theo từ khóa + ngân sách trong câu hỏi của khách
    private List<ChatSource> retrieveLiveCatalog(String question) {
        BigDecimal maxBudget = extractBudgetVnd(question);
        List<String> tokens = extractSearchTokens(question);
        List<Long> semanticIds = vectorSearch == null ? List.of() : vectorSearch.searchProducts(question, 5);

        StringBuilder sql = new StringBuilder("""
                SELECT p.id AS product_id, p.name AS product_name, v.sku, v.name AS variant_name,
                       c.name AS category_name, v.price,
                       COALESCE((SELECT SUM(ib.available_quantity)
                                 FROM inventory_balances ib
                                 JOIN warehouses w ON w.id = ib.warehouse_id AND w.is_active = 1
                                 WHERE ib.variant_id = v.id), 0) AS available_quantity,
                       (SELECT pi.url FROM product_images pi
                        WHERE pi.product_id = p.id AND (pi.variant_id = v.id OR pi.variant_id IS NULL)
                        ORDER BY CASE WHEN pi.variant_id = v.id THEN 0 ELSE 1 END, pi.sort_order ASC, pi.id ASC
                        LIMIT 1) AS image_url
                FROM product_variants v
                JOIN products p ON p.id = v.product_id
                JOIN categories c ON c.id = p.category_id
                WHERE p.status = 'ACTIVE' AND v.status = 'ACTIVE'
                """);
        var args = new ArrayList<Object>();

        if (maxBudget != null) {
            sql.append(" AND v.price <= ?");
            args.add(maxBudget);
        }
        if (!semanticIds.isEmpty()) {
            sql.append(" AND p.id IN (").append(String.join(",", java.util.Collections.nCopies(semanticIds.size(), "?"))).append(")");
            args.addAll(semanticIds);
        } else if (!tokens.isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < tokens.size(); i++) {
                if (i > 0) sql.append(" OR ");
                String like = "%" + tokens.get(i).replace("=", "==").replace("%", "=%").replace("_", "=_") + "%";
                sql.append("(LOWER(p.name) LIKE ? ESCAPE '=' OR LOWER(v.name) LIKE ? ESCAPE '=' OR LOWER(c.name) LIKE ? ESCAPE '=' OR LOWER(COALESCE(p.short_description, '')) LIKE ? ESCAPE '=')");
                args.add(like);
                args.add(like);
                args.add(like);
                args.add(like);
            }
            sql.append(")");
        }
        sql.append(" ORDER BY available_quantity DESC, v.price ASC LIMIT 5");

        var rows = jdbc.query(sql.toString(), (rs, r) -> new ChatSource(
                rs.getLong("product_id"),
                rs.getString("product_name"),
                rs.getString("sku"),
                rs.getString("variant_name"),
                rs.getString("category_name"),
                rs.getBigDecimal("price").setScale(2, RoundingMode.HALF_UP).toPlainString(),
                Math.max(0, rs.getInt("available_quantity")),
                rs.getString("image_url")), args.toArray());

        // Nếu không khớp từ khóa cụ thể nhưng có ngân sách hoặc câu hỏi chung thì lấy vài mẫu đang có sẵn
        if (rows.isEmpty() && (!tokens.isEmpty() || !semanticIds.isEmpty())) {
            String fallbackSql = """
                    SELECT p.id AS product_id, p.name AS product_name, v.sku, v.name AS variant_name,
                           c.name AS category_name, v.price,
                           COALESCE((SELECT SUM(ib.available_quantity)
                                     FROM inventory_balances ib
                                     JOIN warehouses w ON w.id = ib.warehouse_id AND w.is_active = 1
                                     WHERE ib.variant_id = v.id), 0) AS available_quantity,
                           (SELECT pi.url FROM product_images pi
                            WHERE pi.product_id = p.id
                            ORDER BY pi.sort_order ASC, pi.id ASC LIMIT 1) AS image_url
                    FROM product_variants v
                    JOIN products p ON p.id = v.product_id
                    JOIN categories c ON c.id = p.category_id
                    WHERE p.status = 'ACTIVE' AND v.status = 'ACTIVE'
                    """ + (maxBudget != null ? " AND v.price <= ?" : "") + " ORDER BY available_quantity DESC, v.price ASC LIMIT 4";
            Object[] fbArgs = maxBudget != null ? new Object[]{maxBudget} : new Object[]{};
            return jdbc.query(fallbackSql, (rs, r) -> new ChatSource(
                    rs.getLong("product_id"),
                    rs.getString("product_name"),
                    rs.getString("sku"),
                    rs.getString("variant_name"),
                    rs.getString("category_name"),
                    rs.getBigDecimal("price").setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    Math.max(0, rs.getInt("available_quantity")),
                    rs.getString("image_url")), fbArgs);
        }
        return rows;
    }

    // Gọi Google Gemini nếu đã cấu hình GEMINI_API_KEY; nếu chưa cấu hình hoặc lỗi mạng thì báo rõ trạng thái thật
    private AiOutcome generateReply(String question, List<ChatMessageView> history, List<ChatSource> sources) {
        if (!isAiConfigured()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Trợ lý AI hiện chưa được kích hoạt khóa API (GEMINI_API_KEY). ");
            if (sources.isEmpty()) {
                sb.append("Chưa tìm thấy mẫu nội thất phù hợp trong danh mục hiện tại. Bạn có thể thử từ khóa như sofa, bàn trà, ghế gỗ hoặc ghi rõ ngân sách.");
            } else {
                sb.append("Dưới đây là các sản phẩm khớp từ dữ liệu giá & tồn kho hiện hành của Nếp Nhà để bạn tham khảo trực tiếp:");
            }
            return new AiOutcome(sb.toString(), "UNCONFIGURED");
        }

        String prompt = buildPrompt(question, history, sources);
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent";
            // Đặt maxOutputTokens đủ rộng (2048) để các model có thinking tokens không bị cắt cụt câu trả lời tiếng Việt
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                    "generationConfig", Map.of("temperature", 0.4, "maxOutputTokens", 2048));

            String raw = httpClient.post()
                    .uri(url)
                    .header("x-goog-api-key", geminiApiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(raw);
            String replyText = extractReplyText(root);
            if (replyText != null) {
                return new AiOutcome(replyText, geminiModel);
            }
        } catch (Exception ex) {
            log.warn("Gemini chat unavailable; using live catalog fallback.");
        }

        // Fallback khi AI tạm lỗi hoặc timeout: vẫn trả thông tin rõ ràng kèm danh sách sản phẩm thật từ DB
        String fallback = sources.isEmpty()
                ? "Trợ lý AI đang tạm bận và chưa tìm thấy mẫu phù hợp. Bạn vui lòng mô tả rõ hơn về phòng, chất liệu hoặc tầm giá nhé."
                : "Trợ lý AI đang tạm bận, nhưng mình đã lọc nhanh các sản phẩm nội thất phù hợp kèm giá và tồn kho hiện tại ở bên dưới để bạn xem trước.";
        return new AiOutcome(fallback, "FALLBACK");
    }

    private String buildPrompt(String question, List<ChatMessageView> history, List<ChatSource> sources) {
        var sb = new StringBuilder();
        sb.append("Bạn là nhân viên tư vấn nội thất của cửa hàng Nếp Nhà. ");
        sb.append("Chỉ tư vấn dựa trên danh sách sản phẩm và giá/tồn kho thật bên dưới, không bịa giá hay mẫu không có.\n\n");
        sb.append("Sản phẩm hiện có:\n");
        if (sources.isEmpty()) {
            sb.append("- Hiện chưa có sản phẩm khớp trực tiếp.\n");
        } else {
            for (var s : sources) {
                sb.append(String.format("- %s (%s, SKU %s, danh mục %s): giá %s VND, tồn khả dụng %d\n",
                        s.productName(), s.variantName(), s.sku(), s.categoryName(), s.price(), s.availableQuantity()));
            }
        }
        if (!history.isEmpty()) {
            sb.append("\nHội thoại gần đây:\n");
            for (var msg : history) {
                sb.append(msg.role()).append(": ").append(msg.content()).append('\n');
            }
        }
        sb.append("\nCâu hỏi mới của khách: ").append(question)
                .append("\nTrả lời ngắn gọn, trọn vẹn câu, tự nhiên bằng tiếng Việt.");
        return sb.toString();
    }

    // Ghép toàn bộ các part văn bản thật của Gemini (bỏ qua part thought nội bộ nếu model có bật thinking)
    public static String extractReplyText(JsonNode root) {
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray()) {
            return null;
        }
        var sb = new StringBuilder();
        for (JsonNode part : parts) {
            if (part.path("thought").asBoolean(false)) {
                continue;
            }
            JsonNode textNode = part.path("text");
            if (textNode.isTextual() && !textNode.asText().isBlank()) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(textNode.asText().trim());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    // Nhận diện ngân sách dạng "10 triệu", "5.5tr" trong câu hỏi để lọc giá bán
    public static BigDecimal extractBudgetVnd(String text) {
        Matcher m = BUDGET_MILLION.matcher(text);
        if (!m.find()) return null;
        try {
            BigDecimal millions = new BigDecimal(m.group(1).replace(',', '.'));
            if (millions.signum() <= 0 || millions.compareTo(new BigDecimal("1000")) > 0) return null;
            return millions.multiply(new BigDecimal("1000000"));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // Tách tối đa 4 từ khóa nội thất có nghĩa từ câu hỏi của khách
    public static List<String> extractSearchTokens(String text) {
        Set<String> stopWords = Set.of(
                "toi", "tôi", "minh", "mình", "can", "cần", "mua", "tim", "tìm", "tu", "tư", "van", "vấn",
                "cho", "phong", "phòng", "duoi", "dưới", "tren", "trên", "trieu", "triệu", "gia", "giá",
                "nao", "nào", "tot", "tốt", "dep", "đẹp", "khong", "không", "co", "có", "voi", "với", "mot", "một");
        String[] parts = text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+");
        var tokens = new ArrayList<String>();
        for (String p : parts) {
            if (p.length() >= 2 && !stopWords.contains(p) && !p.matches("\\d+") && !tokens.contains(p)) {
                tokens.add(p);
                if (tokens.size() >= 4) break;
            }
        }
        return tokens;
    }

    private String serializeSources(List<ChatSource> sources) {
        try {
            return mapper.writeValueAsString(sources);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<ChatSource> parseSources(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode arr = mapper.readTree(raw);
            if (!arr.isArray()) return List.of();
            var list = new ArrayList<ChatSource>();
            for (JsonNode n : arr) {
                list.add(new ChatSource(
                        n.path("productId").asLong(),
                        n.path("productName").asText(""),
                        n.path("sku").asText(""),
                        n.path("variantName").asText(""),
                        n.path("categoryName").asText(""),
                        n.path("price").asText("0.00"),
                        n.path("availableQuantity").asInt(0),
                        n.path("imageUrl").isNull() ? null : n.path("imageUrl").asText(null)));
            }
            return list;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String toIso(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().atOffset(ZoneOffset.UTC).toString();
    }

    private record ConversationRow(long id, String publicId, String title, String status) {}
    private record AiOutcome(String reply, String model) {}
}
