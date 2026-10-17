package ru.rentoptima.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI-чат помощника включается только явно: {@code APP_AI_CHAT_ENABLED=true}. Выключен (по
 * умолчанию) — в меню и на страницах его нет, /chat и /api/chat отвечают 404, запросов к AI
 * от чата нет. Свободный текст из чата ушёл бы в AI-сервис за рубежом, а документы обещают,
 * что трансграничной передачи нет (patches/INTEGRATION_LEGAL.md, п. 5).
 */
@Component
public class AiChatFlag {

    private final boolean enabled;

    public AiChatFlag(@Value("${app.ai-chat.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }
}
