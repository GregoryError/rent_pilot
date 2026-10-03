package ru.rentoptima.controller;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Настройки виджета: ссылки на фотографии")
class WidgetAdminControllerTest {

    @Test
    @DisplayName("остаются только https-ссылки без пробелов и кавычек, не больше десяти")
    void parsePhotos() {
        ArrayNode photos = WidgetAdminController.parsePhotos("""
                https://example.com/a.jpg

                http://example.com/insecure.jpg
                javascript:alert(1)
                https://example.com/b.jpg" onerror="x
                  https://example.com/c.jpg\s
                """);

        assertThat(photos).hasSize(2);
        assertThat(photos.get(0).asText()).isEqualTo("https://example.com/a.jpg");
        assertThat(photos.get(1).asText()).isEqualTo("https://example.com/c.jpg");

        assertThat(WidgetAdminController.parsePhotos("https://e.com/x.jpg\n".repeat(15))).hasSize(10);
        assertThat(WidgetAdminController.parsePhotos(null)).isEmpty();
    }
}
