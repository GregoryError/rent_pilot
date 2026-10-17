package ru.rentoptima.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ограничитель частоты для публичных эндпоинтов виджета: счётчик на ключ
 * (обычно IP) в фиксированном окне. В памяти процесса — у нас один инстанс;
 * после рестарта счётчики обнуляются, для защиты от перебора этого достаточно.
 */
@Component
public class PublicRateLimiter {

    /** Потолок числа ключей: при переполнении выбрасываем закрытые окна. */
    private static final int MAX_KEYS = 20_000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public boolean allow(String key, int limit, long windowMillis) {
        return allow(key, limit, windowMillis, System.currentTimeMillis());
    }

    boolean allow(String key, int limit, long windowMillis, long now) {
        if (windows.size() > MAX_KEYS) {
            windows.values().removeIf(w -> now >= w.resetAt);
        }
        Window w = windows.compute(key, (k, old) ->
                old == null || now >= old.resetAt ? new Window(now + windowMillis) : old);
        synchronized (w) {
            if (w.count >= limit) return false;
            w.count++;
            return true;
        }
    }

    private static final class Window {
        final long resetAt;
        int count;

        Window(long resetAt) {
            this.resetAt = resetAt;
        }
    }
}
