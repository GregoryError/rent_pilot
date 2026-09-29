package ru.rentoptima.channel.ical;

/**
 * Загрузчик содержимого iCal-фида по URL.
 * <p>
 * Вынесен интерфейсом ради тестируемости: в юнит-тестах подставляется стаб,
 * возвращающий заранее заготовленный .ics, без сетевых вызовов.
 */
public interface ICalFeedFetcher {

    /**
     * @return тело фида как текст
     * @throws ru.rentoptima.channel.ChannelSyncException при сетевой ошибке или non-2xx
     */
    String fetch(String url);
}
