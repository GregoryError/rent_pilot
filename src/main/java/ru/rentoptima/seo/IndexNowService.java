package ru.rentoptima.seo;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * IndexNow: сообщает поисковикам, что страница появилась, изменилась или снята с
 * индексации, — не дожидаясь обхода. Яндекс протокол поддерживает и передаёт адреса
 * остальным участникам.
 * <p>
 * Работает только на prod и только с ключом ({@code INDEXNOW_KEY}); иначе молча ничего не
 * делает. Отправка — в фоне: ответ поисковика не должен задерживать сохранение настроек,
 * а его ошибка — ломать его.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexNowService {

    static final String ENDPOINT = "https://yandex.com/indexnow";
    /** Сколько дней после правки текста страница считается «только что изменённой». */
    static final int FRESH_DAYS = 2;

    private final SiteSettings site;
    private final RestClient.Builder restClientBuilder;

    public boolean enabled() {
        return site.isProduction() && validKey(site.getIndexNowKey());
    }

    /** Ключ по протоколу: 8–128 символов, латиница, цифры и дефис. */
    static boolean validKey(String key) {
        return key != null && key.matches("[A-Za-z0-9-]{8,128}");
    }

    /** Пути страниц сайта («/b/loft»), не полные адреса. */
    public void submit(Collection<String> paths) {
        if (!enabled() || paths == null || paths.isEmpty()) return;
        List<String> urls = new LinkedHashSet<>(paths).stream().map(p -> site.getBaseUrl() + p).toList();
        Map<String, Object> body = Map.of(
                "host", URI.create(site.getBaseUrl()).getHost(),
                "key", site.getIndexNowKey(),
                "keyLocation", site.getBaseUrl() + "/" + site.getIndexNowKey() + ".txt",
                "urlList", urls);
        CompletableFuture.runAsync(() -> {
            try {
                var response = restClientBuilder.build().post().uri(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON).body(body)
                        .retrieve().toBodilessEntity();
                log.info("IndexNow: {} → {}", urls, response.getStatusCode().value());
            } catch (Exception e) {
                log.warn("IndexNow: не удалось отправить {}: {}", urls, e.getMessage());
            }
        });
    }

    public void submit(String... paths) {
        submit(List.of(paths));
    }

    /**
     * Страницы сайта меняются только с выкладкой, поэтому о них сообщаем при старте — и
     * лишь о тех, чей текст правили в последние дни ({@code lastmod} в {@link SitePages}).
     * Перезапуск без правок текста ничего не отправляет: IndexNow просит не слать адреса,
     * которые не менялись.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void announceChangedPages() {
        if (!enabled()) return;
        submit(recentlyChanged(java.time.LocalDate.now()));
    }

    static List<String> recentlyChanged(java.time.LocalDate today) {
        return SitePages.indexed().stream()
                .filter(p -> !p.lastmod().isBefore(today.minusDays(FRESH_DAYS)) && !p.lastmod().isAfter(today))
                .map(SitePages.Page::path).toList();
    }
}
