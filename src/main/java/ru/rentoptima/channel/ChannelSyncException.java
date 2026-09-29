package ru.rentoptima.channel;

/**
 * Ошибка синхронизации канала. Не фатальна для приложения:
 * {@code ChannelSyncService} ловит её, пишет в {@code channels.last_error}
 * и продолжает с остальными каналами — падение одной площадки не должно
 * останавливать синхронизацию других.
 */
public class ChannelSyncException extends RuntimeException {

    public ChannelSyncException(String message) {
        super(message);
    }

    public ChannelSyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
