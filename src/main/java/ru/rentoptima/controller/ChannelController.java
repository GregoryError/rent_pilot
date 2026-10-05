package ru.rentoptima.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ru.rentoptima.channel.ChannelSyncException;
import ru.rentoptima.channel.ical.UrlSafetyGuard;
import ru.rentoptima.entity.Channel;
import ru.rentoptima.entity.Property;
import ru.rentoptima.entity.UnitType;
import ru.rentoptima.repository.CalendarBlockRepository;
import ru.rentoptima.repository.ChannelRepository;
import ru.rentoptima.repository.PropertyRepository;
import ru.rentoptima.repository.UnitTypeRepository;
import ru.rentoptima.security.AuthContext;
import ru.rentoptima.service.ChannelPalette;
import ru.rentoptima.service.ChannelRateLimiter;
import ru.rentoptima.service.ChannelSyncService;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Controller
@RequestMapping("/settings/channels")
@RequiredArgsConstructor
public class ChannelController {

    private static final DateTimeFormatter FULL_DT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final ChannelRepository channelRepo;
    private final CalendarBlockRepository blockRepo;
    private final UnitTypeRepository unitTypeRepo;
    private final PropertyRepository propertyRepo;
    private final ChannelSyncService syncService;
    private final ChannelRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    @GetMapping
    public String list(Model model, HttpServletRequest request) {
        Long tenantId = AuthContext.tenantId();
        // Канал виджета настраивается в «Странице бронирования», здесь ему делать нечего
        List<Channel> channels = channelRepo.findByTenantIdAndActiveTrue(tenantId).stream()
                .filter(c -> c.getChannelType() != Channel.ChannelType.WIDGET)
                .toList();
        List<UnitType> unitTypes = unitTypeRepo.findByTenantIdAndActiveTrue(tenantId);
        List<Property> properties = propertyRepo.findByTenantIdAndActiveTrue(tenantId);

        Map<Long, String> unitTypeLabels = buildUnitTypeLabels(unitTypes, properties);
        String baseUrl = ServletUriComponentsBuilder
                .fromContextPath(request).build().toUriString();

        List<ChannelView> views = new ArrayList<>(channels.size());
        for (Channel c : channels) {
            views.add(buildView(c, unitTypeLabels, baseUrl));
        }

        List<UnitTypeOption> unitTypeOptions = unitTypes.stream()
                .map(ut -> new UnitTypeOption(ut.getId(), unitTypeLabels.get(ut.getId())))
                .toList();

        model.addAttribute("activePage", "channels");
        model.addAttribute("channels", views);
        model.addAttribute("unitTypes", unitTypeOptions);
        model.addAttribute("hasUnitTypes", !unitTypes.isEmpty());
        return "pages/settings/channels";
    }

    @PostMapping
    @Transactional
    public String create(@RequestParam String name,
                         @RequestParam Long unitTypeId,
                         @RequestParam String importUrl,
                         @RequestParam(required = false) Integer syncIntervalMinutes,
                         RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();

        UnitType ut = unitTypeRepo.findById(unitTypeId).orElse(null);
        if (ut == null || !tenantId.equals(ut.getTenantId())) {
            redirect.addFlashAttribute("error", "Категория не найдена");
            return "redirect:/settings/channels";
        }

        try {
            UrlSafetyGuard.normalizeAndCheckShape(importUrl);
        } catch (ChannelSyncException e) {
            redirect.addFlashAttribute("error", "URL импорта: " + e.getMessage());
            return "redirect:/settings/channels";
        }

        int interval = syncIntervalMinutes == null ? 30 : Math.max(15, syncIntervalMinutes);

        ObjectNode config = objectMapper.createObjectNode();
        config.put("import_url", importUrl.trim());
        config.put("sync_interval_minutes", interval);

        Channel channel = new Channel();
        channel.setTenantId(tenantId);
        channel.setUnitTypeId(unitTypeId);
        channel.setChannelType(Channel.ChannelType.ICAL);
        channel.setName(name.trim());
        channel.setColor(ChannelPalette.randomColor(
                channelRepo.findByTenantIdAndActiveTrue(tenantId).stream().map(Channel::getColor).toList()));
        channel.setConfigJson(config);
        channel.setSyncEnabled(true);
        channel.setActive(true);
        channel.setCreatedAt(LocalDateTime.now());
        channel.setUpdatedAt(LocalDateTime.now());
        channelRepo.save(channel);

        syncService.ensureExportSecret(channel.getId());

        log.info("Channel создан: tenant={}, type=ICAL, name='{}', unitType={}",
                tenantId, name, unitTypeId);
        redirect.addFlashAttribute("success", "Канал «" + name + "» подключён");
        return "redirect:/settings/channels";
    }

    @PostMapping("/{id}/sync")
    public String syncNow(@PathVariable Long id, RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        Channel channel = channelRepo.findById(id).orElse(null);
        if (channel == null || !tenantId.equals(channel.getTenantId())) {
            redirect.addFlashAttribute("error", "Канал не найден");
            return "redirect:/settings/channels";
        }
        if (!rateLimiter.tryAcquire(id)) {
            long wait = rateLimiter.secondsUntilReady(id);
            redirect.addFlashAttribute("error",
                    "Подождите " + wait + " сек между ручными синхронизациями");
            return "redirect:/settings/channels";
        }
        syncService.syncChannel(id);
        redirect.addFlashAttribute("success", "Синхронизация запущена");
        return "redirect:/settings/channels";
    }

    @PostMapping("/{id}/toggle")
    @Transactional
    public String toggle(@PathVariable Long id, RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        channelRepo.findById(id).ifPresent(c -> {
            if (!tenantId.equals(c.getTenantId())) return;
            c.setSyncEnabled(!Boolean.TRUE.equals(c.getSyncEnabled()));
            c.setUpdatedAt(LocalDateTime.now());
            channelRepo.save(c);
        });
        redirect.addFlashAttribute("success", "Настройка синхронизации изменена");
        return "redirect:/settings/channels";
    }

    @PostMapping("/{id}/color")
    public String color(@PathVariable Long id, @RequestParam String color, RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        Channel channel = channelRepo.findById(id).orElse(null);
        if (channel == null || !tenantId.equals(channel.getTenantId())) {
            redirect.addFlashAttribute("error", "Канал не найден");
            return "redirect:/settings/channels";
        }
        if (!ChannelPalette.contains(color)) {
            redirect.addFlashAttribute("error", "Выберите цвет из палитры");
            return "redirect:/settings/channels";
        }
        channel.setColor(color.toUpperCase());
        channel.setUpdatedAt(LocalDateTime.now());
        channelRepo.save(channel);
        redirect.addFlashAttribute("success", "Цвет канала «" + channel.getName() + "» изменён");
        return "redirect:/settings/channels";
    }

    @PostMapping("/{id}/regenerate-secret")
    public String regenerate(@PathVariable Long id, RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        Channel channel = channelRepo.findById(id).orElse(null);
        if (channel == null || !tenantId.equals(channel.getTenantId())) {
            redirect.addFlashAttribute("error", "Канал не найден");
            return "redirect:/settings/channels";
        }
        syncService.regenerateExportSecret(id);
        redirect.addFlashAttribute("success",
                "Новый секрет выдан. Старый URL больше не работает — обновите настройку на площадке.");
        return "redirect:/settings/channels";
    }

    @PostMapping("/{id}/delete")
    @Transactional
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        Long tenantId = AuthContext.tenantId();
        Channel c = channelRepo.findById(id).orElse(null);
        if (c == null || !tenantId.equals(c.getTenantId())) {
            redirect.addFlashAttribute("error", "Канал не найден");
            return "redirect:/settings/channels";
        }
        c.setActive(false);
        c.setSyncEnabled(false);
        c.setUpdatedAt(LocalDateTime.now());
        channelRepo.save(c);
        // Занятость, импортированная с канала, без него уже не обновится и не снимется —
        // оставлять её значит держать даты закрытыми в шахматке и в фидах других каналов.
        int removed = blockRepo.deleteByChannel(c.getId());
        redirect.addFlashAttribute("success", removed == 0
                ? "Канал удалён, синхронизация остановлена"
                : "Канал удалён, синхронизация остановлена. Снято блокировок с этой площадки: " + removed);
        return "redirect:/settings/channels";
    }

    private Map<Long, String> buildUnitTypeLabels(List<UnitType> unitTypes, List<Property> properties) {
        Map<Long, Property> propertyById = new HashMap<>();
        for (Property p : properties) propertyById.put(p.getId(), p);
        Map<Long, String> labels = new HashMap<>();
        for (UnitType ut : unitTypes) {
            Property p = propertyById.get(ut.getPropertyId());
            StringBuilder sb = new StringBuilder();
            if (p != null) sb.append(p.getName()).append(" / ");
            sb.append(ut.getName());
            if (ut.getUnitCount() != null && ut.getUnitCount() > 1) {
                sb.append(" (×").append(ut.getUnitCount()).append(")");
            }
            labels.put(ut.getId(), sb.toString());
        }
        return labels;
    }

    private ChannelView buildView(Channel c, Map<Long, String> unitTypeLabels, String baseUrl) {
        String importUrl = extractString(c, "import_url");
        int interval = extractIntervalMinutes(c);

        String exportUrl = c.getExportSecret() != null
                ? baseUrl + "/ical/" + c.getExportSecret() + ".ics"
                : null;

        Status status = resolveStatus(c);
        String lastSyncRelative = formatRelative(c.getLastSyncAt(), LocalDateTime.now());
        String lastSyncExact = c.getLastSyncAt() != null ? c.getLastSyncAt().format(FULL_DT) : null;

        boolean canSyncNow = c.getChannelType() != Channel.ChannelType.MANUAL
                && Boolean.TRUE.equals(c.getActive());

        String color = ChannelPalette.colorOf(c);
        List<PaletteColor> palette = new ArrayList<>();
        for (String p : ChannelPalette.COLORS) {
            palette.add(new PaletteColor(p, "background: " + p,
                    p.equals(color) ? "color-swatch is-selected" : "color-swatch"));
        }

        return new ChannelView(
                c.getId(),
                c.getName(),
                ChannelPalette.letterOf(c.getName()),
                "background: " + color,
                palette,
                c.getChannelType().name(),
                unitTypeLabels.getOrDefault(c.getUnitTypeId(), "— unit_type #" + c.getUnitTypeId() + " —"),
                importUrl,
                exportUrl,
                "copy-import-" + c.getId(),
                "copy-export-" + c.getId(),
                interval,
                status.text(),
                status.cssClass(),
                c.getLastError(),
                lastSyncRelative,
                lastSyncExact,
                c.getLastSyncImported(),
                c.getLastSyncRemoved(),
                Boolean.TRUE.equals(c.getSyncEnabled()),
                canSyncNow
        );
    }

    private Status resolveStatus(Channel c) {
        if (!Boolean.TRUE.equals(c.getSyncEnabled())) {
            return new Status("Синхронизация выключена", "tag--gray");
        }
        if (c.getLastError() != null && !c.getLastError().isBlank()) {
            return new Status("Ошибка", "tag--red");
        }
        if (c.getLastSyncAt() == null) {
            return new Status("Ещё не синхронизирован", "tag--gray");
        }
        return new Status("OK", "tag--green");
    }

    private String extractString(Channel c, String field) {
        if (c.getConfigJson() == null) return null;
        var node = c.getConfigJson().get(field);
        return (node == null || node.isNull()) ? null : node.asText();
    }

    private int extractIntervalMinutes(Channel c) {
        if (c.getConfigJson() == null) return 30;
        var node = c.getConfigJson().get("sync_interval_minutes");
        if (node == null || !node.canConvertToInt()) return 30;
        return Math.max(15, node.asInt(30));
    }

    private String formatRelative(LocalDateTime time, LocalDateTime now) {
        if (time == null) return "никогда";
        long minutes = ChronoUnit.MINUTES.between(time, now);
        if (minutes < 1) return "только что";
        if (minutes < 60) return minutes + " мин назад";
        long hours = ChronoUnit.HOURS.between(time, now);
        if (hours < 24) return hours + " ч назад";
        long days = ChronoUnit.DAYS.between(time, now);
        if (days < 7) return days + " дн назад";
        return time.format(FULL_DT);
    }

    public record Status(String text, String cssClass) {}

    public record UnitTypeOption(Long id, String label) {}

    public record PaletteColor(String hex, String style, String cssClass) {}

    public record ChannelView(
            Long id,
            String name,
            String letter,
            String colorStyle,
            List<PaletteColor> palette,
            String channelType,
            String unitTypeLabel,
            String importUrl,
            String exportUrl,
            String importUrlInputId,
            String exportUrlInputId,
            int syncIntervalMinutes,
            String statusText,
            String statusCssClass,
            String lastError,
            String lastSyncRelative,
            String lastSyncExact,
            Integer lastSyncImported,
            Integer lastSyncRemoved,
            boolean syncEnabled,
            boolean canSyncNow
    ) {}
}
