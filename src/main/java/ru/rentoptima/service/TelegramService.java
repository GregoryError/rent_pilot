package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Отправка уведомлений в Telegram от имени бота tenant'а.
 * <p>
 * Токен бота ({@code tg_bot_token}) и чат ({@code tg_chat_id}) лежат в
 * system_settings. Бот свой у каждого tenant'а: хост создаёт его в @BotFather,
 * поэтому сообщения приходят от его собственного бота, а не от общего.
 * <p>
 * Токен — часть URL запроса, поэтому тексты ошибок перед логом/показом
 * проходят через {@link #sanitize}.
 */
@Slf4j
@Service
public class TelegramService {

    public static final String KEY_TOKEN = "tg_bot_token";
    public static final String KEY_CHAT_ID = "tg_chat_id";
    /** Адрес, по которому tenant открывает сервис — для ссылок в сообщениях. */
    public static final String KEY_BASE_URL = "public_base_url";

    private static final String API = "https://api.telegram.org/bot";
    private static final Pattern TOKEN_FORMAT = Pattern.compile("\\d{5,}:[A-Za-z0-9_-]{30,}");
    private static final Pattern CHAT_ID_FORMAT = Pattern.compile("-?\\d{1,20}|@[A-Za-z0-9_]{5,32}");
    /** Лимит Telegram на одно сообщение — 4096 символов. */
    private static final int MAX_TEXT = 4000;

    private final SettingsService settings;
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public TelegramService(SettingsService settings) {
        this.settings = settings;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public static boolean isValidToken(String token) {
        return token != null && TOKEN_FORMAT.matcher(token).matches();
    }

    public static boolean isValidChatId(String chatId) {
        return chatId != null && CHAT_ID_FORMAT.matcher(chatId).matches();
    }

    public boolean isConfigured(Long tenantId) {
        return token(tenantId) != null && chatId(tenantId) != null;
    }

    public boolean hasToken(Long tenantId) {
        return token(tenantId) != null;
    }

    public String chatId(Long tenantId) {
        String chatId = settings.getValue(tenantId, KEY_CHAT_ID);
        return isValidChatId(chatId) ? chatId : null;
    }

    /** Отправляет сообщение в чат tenant'а. Никогда не бросает исключений. */
    public SendResult send(Long tenantId, String text) {
        String token = token(tenantId);
        String chatId = chatId(tenantId);
        if (token == null || chatId == null) {
            return SendResult.failed("Telegram не настроен");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text);
        body.put("disable_web_page_preview", true);

        try {
            client.post()
                    .uri(URI.create(API + token + "/sendMessage"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return SendResult.sent();
        } catch (Exception e) {
            String error = describe(e, token);
            log.warn("Telegram sendMessage failed for tenant {}: {}", tenantId, error);
            return SendResult.failed(error);
        }
    }

    /**
     * Ищет чат среди последних обращений к боту: хост пишет боту /start (или
     * добавляет его в группу), после чего чат виден в getUpdates.
     */
    public Optional<Chat> detectChat(Long tenantId) {
        String token = token(tenantId);
        if (token == null) throw new IllegalStateException("Сначала сохраните токен бота");

        JsonNode response;
        try {
            response = client.get()
                    .uri(URI.create(API + token + "/getUpdates"))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new IllegalStateException(describe(e, token));
        }

        Chat last = null;
        JsonNode updates = response == null ? null : response.get("result");
        if (updates != null && updates.isArray()) {
            for (JsonNode update : updates) {
                for (String field : new String[]{"message", "channel_post", "my_chat_member"}) {
                    JsonNode chat = update.path(field).path("chat");
                    if (chat.hasNonNull("id")) {
                        last = new Chat(chat.get("id").asText(), chatTitle(chat));
                    }
                }
            }
        }
        return Optional.ofNullable(last);
    }

    private static String chatTitle(JsonNode chat) {
        if (chat.hasNonNull("title")) return chat.get("title").asText();
        if (chat.hasNonNull("username")) return "@" + chat.get("username").asText();
        return "личный чат";
    }

    private String token(Long tenantId) {
        String token;
        try {
            token = settings.getSecret(tenantId, KEY_TOKEN);
        } catch (Exception e) {
            log.warn("Не удалось расшифровать {} для tenant {}", KEY_TOKEN, tenantId);
            return null;
        }
        return isValidToken(token) ? token : null;
    }

    /** Человекочитаемая причина: у Telegram она лежит в поле description ответа. */
    private String describe(Exception e, String token) {
        if (e instanceof RestClientResponseException re) {
            try {
                JsonNode json = mapper.readTree(re.getResponseBodyAsString());
                if (json.hasNonNull("description")) {
                    return "Telegram: " + sanitize(json.get("description").asText(), token);
                }
            } catch (Exception ignored) {
                // тело не JSON — покажем статус
            }
            return "Telegram ответил " + re.getStatusCode().value();
        }
        return sanitize(e.getMessage(), token);
    }

    static String sanitize(String message, String token) {
        if (message == null) return "нет соединения с Telegram";
        return token == null ? message : message.replace(token, "***");
    }

    public record SendResult(boolean ok, String error) {
        static SendResult sent() {
            return new SendResult(true, null);
        }

        static SendResult failed(String error) {
            return new SendResult(false, error);
        }
    }

    public record Chat(String id, String title) {}
}
