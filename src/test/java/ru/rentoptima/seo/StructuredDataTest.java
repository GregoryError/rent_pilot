package ru.rentoptima.seo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.rentoptima.seo.StructuredData.Crumb;
import ru.rentoptima.seo.StructuredData.Lodging;
import ru.rentoptima.seo.StructuredData.Question;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Микроразметка JSON-LD")
class StructuredDataTest {

    private static final String BASE = "https://optirent.ru";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Lodging loft(String description, String city, BigDecimal price) {
        return new Lodging("Лофт у парка", BASE + "/b/loft", description,
                List.of(BASE + "/media/widget/a-960.jpg"), city, List.of("Wi-Fi", "Кухня"), price,
                "14:00", "12:00", true);
    }

    @Test
    @DisplayName("организация и сайт: бренд латиницей и варианты по-русски")
    void organizationAndWebsite() throws Exception {
        JsonNode org = JSON.readTree(StructuredData.organization(BASE));
        assertThat(org.path("@type").asText()).isEqualTo("Organization");
        assertThat(org.path("name").asText()).isEqualTo("OptiRent");
        assertThat(org.path("alternateName")).extracting(JsonNode::asText)
                .containsExactly("ОптиРент", "Оптирент", "Opti Rent");
        assertThat(org.path("url").asText()).isEqualTo("https://optirent.ru/");
        assertThat(org.path("logo").asText()).isEqualTo("https://optirent.ru/icons/icon-512.png");

        JsonNode site = JSON.readTree(StructuredData.website(BASE));
        assertThat(site.path("@type").asText()).isEqualTo("WebSite");
        assertThat(site.path("publisher").path("@id").asText()).isEqualTo(org.path("@id").asText());
    }

    @Test
    @DisplayName("продукт: бесплатное веб-приложение для бизнеса")
    void softwareApplication() throws Exception {
        JsonNode app = JSON.readTree(StructuredData.softwareApplication(BASE, "/", "Календарь бронирования"));
        assertThat(app.path("applicationCategory").asText()).isEqualTo("BusinessApplication");
        assertThat(app.path("operatingSystem").asText()).isEqualTo("Web");
        assertThat(app.path("offers").path("price").asText()).isEqualTo("0");
        assertThat(app.path("offers").path("priceCurrency").asText()).isEqualTo("RUB");
        assertThat(app.path("offers").has("@context")).isFalse();
    }

    @Test
    @DisplayName("вопросы и хлебные крошки")
    void faqAndBreadcrumbs() throws Exception {
        JsonNode faq = JSON.readTree(StructuredData.faqPage(List.of(
                new Question("Это правда бесплатно?", "Да."), new Question("Нужен ли сайт?", "Нет."))));
        assertThat(faq.path("@type").asText()).isEqualTo("FAQPage");
        assertThat(faq.path("mainEntity")).hasSize(2);
        assertThat(faq.path("mainEntity").get(0).path("acceptedAnswer").path("text").asText()).isEqualTo("Да.");

        JsonNode crumbs = JSON.readTree(StructuredData.breadcrumbs(BASE, List.of(
                new Crumb("Главная", "/"), new Crumb("Инструкции", "/instrukcii"),
                new Crumb("Авито", "/instrukcii/avito"))));
        JsonNode items = crumbs.path("itemListElement");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).path("item").asText()).isEqualTo("https://optirent.ru/");
        assertThat(items.get(2).path("position").asInt()).isEqualTo(3);
        assertThat(items.get(2).path("item").asText()).isEqualTo("https://optirent.ru/instrukcii/avito");
    }

    @Test
    @DisplayName("жильё: адрес — только город, цена «от», удобства; рейтингов и отзывов нет")
    void lodging() throws Exception {
        String raw = StructuredData.lodging(loft("Светлый лофт", "Выборг", new BigDecimal("4500.00")));
        JsonNode l = JSON.readTree(raw);

        assertThat(l.path("@type").asText()).isEqualTo("LodgingBusiness");
        assertThat(l.path("address").path("addressLocality").asText()).isEqualTo("Выборг");
        assertThat(l.path("address").has("streetAddress")).isFalse();
        assertThat(l.path("priceRange").asText()).isEqualTo("от 4 500 ₽ за ночь");
        assertThat(l.path("amenityFeature")).hasSize(2);
        assertThat(l.path("amenityFeature").get(0).path("name").asText()).isEqualTo("Wi-Fi");
        assertThat(l.path("image").get(0).asText()).endsWith("a-960.jpg");
        assertThat(l.path("checkinTime").asText()).isEqualTo("14:00");
        assertThat(raw).doesNotContain("aggregateRating", "review", "Rating");
    }

    @Test
    @DisplayName("жильё без города, описания и цены — этих полей в разметке просто нет")
    void lodgingWithoutOptionalFields() throws Exception {
        JsonNode l = JSON.readTree(StructuredData.lodging(loft(" ", null, null)));
        assertThat(l.has("address")).isFalse();
        assertThat(l.has("description")).isFalse();
        assertThat(l.has("priceRange")).isFalse();
        assertThat(l.path("name").asText()).isEqualTo("Лофт у парка");
    }

    @Test
    @DisplayName("текст хозяина с </script> не закрывает тег")
    void scriptTagCannotBeClosed() throws Exception {
        String raw = StructuredData.lodging(loft("Лофт </script><script>alert(1)</script>", "Выборг", null));
        assertThat(raw).doesNotContain("</script>", "<script>");
        assertThat(JSON.readTree(raw).path("description").asText()).isEqualTo("Лофт </script><script>alert(1)</script>");
    }
}
