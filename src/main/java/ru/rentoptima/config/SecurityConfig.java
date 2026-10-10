package ru.rentoptima.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import ru.rentoptima.security.AppUserDetailsService;

@EnableMethodSecurity(prePostEnabled = true)
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final AppUserDetailsService userDetailsService;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers(
                                new AntPathRequestMatcher("/housekeeper/**"),
                                new AntPathRequestMatcher("/ical/**"),
                                // /api/** включает публичный API виджета: запросы идут с чужих
                                // сайтов и из фрейма, сессионной cookie там нет — токену взяться неоткуда
                                new AntPathRequestMatcher("/api/**")))
                // CORS публичного API виджета — по списку сайтов, разрешённых хозяином
                // (бин corsConfigurationSource из WidgetCorsConfig)
                .cors(Customizer.withDefaults())
                // Встраивание в iframe запрещено везде, кроме страницы виджета /b/{slug}/embed:
                // она сама отдаёт frame-ancestors со списком разрешённых сайтов
                .headers(headers -> headers
                        .frameOptions(frame -> frame.disable())
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                new NegatedRequestMatcher(new OrRequestMatcher(
                                        new AntPathRequestMatcher("/b/*/embed"),
                                        // фрейм превью в конструкторе: сам отдаёт SAMEORIGIN
                                        new AntPathRequestMatcher("/settings/widgets/*/frame"))),
                                new XFrameOptionsHeaderWriter(XFrameOptionsHeaderWriter.XFrameOptionsMode.DENY))))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/login",
                                "/register",
                                "/legal/**",
                                "/ical/**",
                                "/book/**",
                                "/b/**",
                                "/widget/**",
                                "/widget.js",
                                "/w.js",
                                "/fonts/**",
                                "/media/**",
                                "/libs/**",
                                "/css/**",
                                "/js/**",
                                "/webjars/**",
                                "/favicon.ico",
                                "/error/**",
                                "/feedback/**",
                                "/api/feedback/**",
                                "/api/widget/**",
                                "/housekeeper/**"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/calendar/grid", true)
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                )
                .userDetailsService(userDetailsService);

        return http.build();
    }
}
