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
                                new AntPathRequestMatcher("/api/**"),
                                new AntPathRequestMatcher("/housekeeper/**"),
                                new AntPathRequestMatcher("/ical/**"),
                                // Заявка с виджета приходит с чужих сайтов и из iframe, где
                                // сессионной cookie нет вовсе — CSRF-токену там взяться неоткуда.
                                new AntPathRequestMatcher("/widget/**")))
                // Preflight-запросы виджета, встроенного на сайт хоста (@CrossOrigin на API)
                .cors(Customizer.withDefaults())
                // Запрет на встраивание в iframe остаётся везде, кроме /widget/** — он для этого и сделан
                .headers(headers -> headers
                        .frameOptions(frame -> frame.disable())
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                new NegatedRequestMatcher(new AntPathRequestMatcher("/widget/**")),
                                new XFrameOptionsHeaderWriter(XFrameOptionsHeaderWriter.XFrameOptionsMode.DENY))))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/login",
                                "/register",
                                "/legal/**",
                                "/ical/**",
                                "/book/**",
                                "/widget/**",
                                "/widget.js",
                                "/libs/**",
                                "/css/**",
                                "/js/**",
                                "/webjars/**",
                                "/favicon.ico",
                                "/error/**",
                                "/feedback/**",
                                "/api/feedback/**",
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
