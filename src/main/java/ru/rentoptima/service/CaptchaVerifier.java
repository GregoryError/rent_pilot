package ru.rentoptima.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Проверка hCaptcha для заявок с виджета.
 * <p>
 * Включается переменными окружения HCAPTCHA_SITE_KEY / HCAPTCHA_SECRET. Пока они
 * не заданы, капча не показывается и не проверяется — от ботов остаются
 * honeypot-поле и лимит заявок с одного IP.
 */
@Slf4j
@Service
public class CaptchaVerifier {

    private static final String VERIFY_URL = "https://api.hcaptcha.com/siteverify";

    private final String siteKey;
    private final String secret;
    private final RestClient client;

    public CaptchaVerifier(@Value("${app.hcaptcha.site-key:}") String siteKey,
                           @Value("${app.hcaptcha.secret:}") String secret) {
        this.siteKey = siteKey == null ? "" : siteKey.trim();
        this.secret = secret == null ? "" : secret.trim();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public boolean enabled() {
        return !siteKey.isEmpty() && !secret.isEmpty();
    }

    /** Публичный ключ для фронта; пустая строка — капча выключена. */
    public String siteKey() {
        return enabled() ? siteKey : "";
    }

    public boolean verify(String token, String remoteIp) {
        if (!enabled()) return true;
        if (token == null || token.isBlank()) return false;

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secret);
        form.add("response", token);
        if (remoteIp != null) form.add("remoteip", remoteIp);

        try {
            JsonNode response = client.post()
                    .uri(VERIFY_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
            return response != null && response.path("success").asBoolean(false);
        } catch (Exception e) {
            // hCaptcha недоступна — заявку не принимаем: иначе это обход капчи её же отказом
            log.warn("hCaptcha verify failed: {}", e.getMessage());
            return false;
        }
    }
}
