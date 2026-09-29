package ru.rentoptima.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.rentoptima.entity.Channel;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Реестр адаптеров: по типу канала отдаёт нужную реализацию.
 * <p>
 * Spring инжектит сюда все бины {@link ChannelAdapter}, поэтому добавление
 * нового канала не требует правок этого класса — достаточно объявить
 * реализацию как {@code @Component}.
 */
@Slf4j
@Component
public class ChannelAdapterRegistry {

    private final Map<Channel.ChannelType, ChannelAdapter> adapters =
            new EnumMap<>(Channel.ChannelType.class);

    public ChannelAdapterRegistry(List<ChannelAdapter> discovered) {
        for (ChannelAdapter a : discovered) {
            ChannelAdapter prev = adapters.put(a.type(), a);
            if (prev != null) {
                throw new IllegalStateException(
                        "Два адаптера на один тип канала %s: %s и %s"
                                .formatted(a.type(), prev.getClass(), a.getClass()));
            }
        }
        log.info("Channel adapters registered: {}", adapters.keySet());
    }

    public Optional<ChannelAdapter> find(Channel.ChannelType type) {
        return Optional.ofNullable(adapters.get(type));
    }

    /** Типы, для которых реализация ещё не написана (Avito на момент MVP). */
    public boolean isSupported(Channel.ChannelType type) {
        return adapters.containsKey(type);
    }
}
