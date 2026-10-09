package ru.rentoptima.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.rentoptima.entity.ManualBlockEcho;

import java.util.List;

public interface ManualBlockEchoRepository extends JpaRepository<ManualBlockEcho, Long> {

    /** Все эхо-связи канала — синхронизация загружает их один раз на прогон. */
    List<ManualBlockEcho> findByChannelId(Long channelId);

    /** На каких каналах видели эту ручную запись — для предупреждения при удалении. */
    List<ManualBlockEcho> findByManualBlockId(Long manualBlockId);
}
