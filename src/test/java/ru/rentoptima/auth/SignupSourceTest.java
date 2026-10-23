package ru.rentoptima.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Источник регистрации: очистка меток и домена")
class SignupSourceTest {

    @Test
    @DisplayName("домен: строчными, без www., путь и параметры отбрасываются, мусор — пусто")
    void domain() {
        assertThat(SignupSource.of(null, null, null, "WWW.VC.RU", null, "optirent.ru").referrerDomain()).isEqualTo("vc.ru");
        assertThat(SignupSource.of(null, null, null, null, "https://t.me/c/1?token=x", "optirent.ru").referrerDomain())
                .isEqualTo("t.me");
        assertThat(SignupSource.of(null, null, null, "javascript:alert(1)", null, "optirent.ru").referrerDomain()).isNull();
        assertThat(SignupSource.of(null, null, null, null, "не адрес", "optirent.ru").referrerDomain()).isNull();
        // свой сайт и его поддомены — не источник
        assertThat(SignupSource.of(null, null, null, "staging.optirent.ru", null, "optirent.ru").referrerDomain()).isNull();
        assertThat(SignupSource.of(null, null, null, null, "https://optirent.ru/instrukcii", "optirent.ru").empty()).isTrue();
    }

    @Test
    @DisplayName("метки: без управляющих символов, до 100 знаков, пустые — null")
    void utm() {
        SignupSource s = SignupSource.of(" vk\n ", "", "x".repeat(300), null, null, null);
        assertThat(s.utmSource()).isEqualTo("vk");
        assertThat(s.utmMedium()).isNull();
        assertThat(s.utmCampaign()).hasSize(100);
    }
}
