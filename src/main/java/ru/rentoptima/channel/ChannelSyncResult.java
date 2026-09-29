package ru.rentoptima.channel;

/**
 * Итог одного прогона синхронизации канала.
 *
 * @param imported число созданных сущностей (блокировок/броней)
 * @param updated  число изменённых сущностей
 * @param removed  число снятых блокировок (событие исчезло из внешнего фида)
 * @param skipped  число событий, осознанно пропущенных (вне окна, кривые даты)
 */
public record ChannelSyncResult(int imported, int updated, int removed, int skipped) {

    public static ChannelSyncResult empty() {
        return new ChannelSyncResult(0, 0, 0, 0);
    }

    public int touched() {
        return imported + updated + removed;
    }

    @Override
    public String toString() {
        return "imported=%d, updated=%d, removed=%d, skipped=%d"
                .formatted(imported, updated, removed, skipped);
    }
}
