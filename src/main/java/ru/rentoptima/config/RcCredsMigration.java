package ru.rentoptima.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import ru.rentoptima.service.SettingsService;

@Component
@RequiredArgsConstructor
public class RcCredsMigration implements CommandLineRunner {

    private final SettingsService settings;

    @Override
    public void run(String... args) {
        Long tenantId = 1L;

        // Если ещё не мигрировано — вбить один раз
        if (settings.getValue(tenantId, "rc_username") == null
                || settings.getValue(tenantId, "rc_username").isBlank()) {

            String envUsername = System.getenv("RC_USERNAME");
            String envPassword = System.getenv("RC_PASSWORD");

            if (envUsername != null && envPassword != null) {
                settings.setValue(tenantId, "rc_username", envUsername);
                settings.setEncryptedValue(tenantId, "rc_password", envPassword);
                System.out.println("✓ RC credentials migrated from env to DB for tenant " + tenantId);
            }
        }
    }
}