package ru.rentoptima.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.function.Function;

/**
 * Объект по id из адреса или формы. Чужой объект для пользователя не существует: ответ — 404,
 * как на несуществующий id, а не 403 — иначе по ответу видно, что такой id у кого-то есть.
 * Таблица «эндпоинт → как проверяется» — patches/INTEGRATION_FIRST_RUN.md.
 */
public final class Tenancy {

    private Tenancy() {
    }

    public static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }

    /** Найденный объект, если он принадлежит тенанту вошедшего пользователя; иначе 404. */
    public static <T> T own(Optional<T> found, Function<T, Long> tenantOf) {
        Long tenantId = AuthContext.tenantId();
        return found.filter(x -> tenantId.equals(tenantOf.apply(x))).orElseThrow(Tenancy::notFound);
    }

    /** Для запросов с tenant_id в условии: пусто — 404. */
    public static <T> T found(Optional<T> found) {
        return found.orElseThrow(Tenancy::notFound);
    }
}
