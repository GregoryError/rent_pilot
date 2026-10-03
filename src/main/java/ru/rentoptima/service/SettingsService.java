package ru.rentoptima.service;

import jakarta.persistence.Column;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.rentoptima.entity.SystemSetting;
import ru.rentoptima.repository.SystemSettingRepository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettingsService {

    private final SystemSettingRepository repo;
    private final ru.rentoptima.util.EncryptionUtil encryptionUtil;

    public List<SystemSetting> getAllForTenant(Long tenantId) {
        return repo.findByTenantIdOrderByKey(tenantId);
    }

    public Map<String, String> getSettingsMap(Long tenantId) {
        return repo.findByTenantIdOrderByKey(tenantId).stream()
                .collect(Collectors.toMap(SystemSetting::getKey, s -> s.getValue() != null ? s.getValue() : ""));
    }

    public String getValue(Long tenantId, String key) {
        return repo.findByTenantIdAndKey(tenantId, key)
                .map(SystemSetting::getValue)
                .orElse(null);
    }

    public int getIntValue(Long tenantId, String key, int defaultValue) {
        String val = getValue(tenantId, key);
        if (val == null || val.isBlank()) return defaultValue;
        try { return Integer.parseInt(val); }
        catch (NumberFormatException e) { return defaultValue; }
    }


    public double getDoubleValue(Long tenantId, String key, double defaultValue) {
        try {
            String val = getValue(tenantId, key);
            return val != null ? Double.parseDouble(val) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Transactional
    public void updateSetting(Long tenantId, String key, String value) {
        repo.findByTenantIdAndKey(tenantId, key).ifPresent(setting -> {
            setting.setValue(value);
            repo.save(setting);
        });
    }

    @Transactional
    public void updateSettings(Long tenantId, Map<String, String> updates) {
        updates.forEach((key, value) -> updateSetting(tenantId, key, value));
    }

    /** Возвращает расшифрованное значение (для is_encrypted=true). */
    public String getEncryptedValue(Long tenantId, String key) {
        String raw = getValue(tenantId, key);
        if (raw == null || raw.isBlank()) return null;
        return encryptionUtil.decrypt(raw);
    }

    /**
     * Секрет, который мог быть сохранён и открытым текстом (старая форма /settings),
     * и зашифрованным: расшифровывает только при encrypted=true.
     */
    public String getSecret(Long tenantId, String key) {
        SystemSetting setting = repo.findByTenantIdAndKey(tenantId, key).orElse(null);
        if (setting == null || setting.getValue() == null || setting.getValue().isBlank()) return null;
        return Boolean.TRUE.equals(setting.getEncrypted())
                ? encryptionUtil.decrypt(setting.getValue())
                : setting.getValue();
    }

    /** Устанавливает зашифрованное значение. */
    @Transactional
    public void setEncryptedValue(Long tenantId, String key, String plainValue) {
        String encrypted = plainValue == null || plainValue.isBlank()
                ? "" : encryptionUtil.encrypt(plainValue);

        var setting = repo.findByTenantIdAndKey(tenantId, key)
                .orElseGet(() -> {
                    var s = new SystemSetting();
                    s.setTenantId(tenantId);
                    s.setKey(key);
                    return s;
                });
        setting.setValue(encrypted);
        setting.setEncrypted(true);  // ← не setIsEncrypted, а setEncrypted (поле называется encrypted)
        repo.save(setting);
    }

    @Transactional
    public void setValue(Long tenantId, String key, String value) {
        var setting = repo.findByTenantIdAndKey(tenantId, key)
                .orElseGet(() -> {
                    var s = new SystemSetting();
                    s.setTenantId(tenantId);
                    s.setKey(key);
                    return s;
                });
        setting.setValue(value);
        repo.save(setting);
    }
}
