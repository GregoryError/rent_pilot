package ru.rentoptima.service;

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
}
