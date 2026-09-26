package io.kvn.client.data

/**
 * «Белые списки»: российские сайты и приложения, которые работают при
 * ограничениях мобильного интернета и часто не открываются через VPN
 * (банки, Госуслуги, маркетплейсы). Их удобно пускать напрямую.
 */
object Whitelist {
    /** Сайты по умолчанию; каждый идёт напрямую вместе с поддоменами. */
    val DEFAULT_DOMAINS: List<String> = listOf(
        // Маркетплейсы и магазины
        "wb.ru", "wildberries.ru", "wbbasket.ru", "wbstatic.net",
        "ozon.ru", "o3.ru", "ozone.ru", "ozonusercontent.com",
        "avito.ru", "avito.st", "lemanapro.ru", "dns-shop.ru", "mvideo.ru", "eldorado.ru", "mm.lognex.ru",
        // VK, Max, OK, Mail.ru
        "vk.com", "vk.ru", "vk.me", "vk.cc", "vkvideo.ru", "userapi.com", "vk-cdn.me", "vk-cdn.net",
        "vkuser.net", "vkuseraudio.net", "vk-portal.net",
        "max.ru", "oneme.ru", "ok.ru", "okcdn.ru", "mail.ru", "myteam.mail.ru",
        // Яндекс
        "yandex.ru", "yandex.net", "yandex.com", "ya.ru", "yastatic.net", "dzen.ru", "zen.yandex.ru",
        "kinopoisk.ru", "auto.ru",
        // Госсервисы
        "gosuslugi.ru", "gu-st.ru", "pos.gosuslugi.ru", "mos.ru", "digital.gov.ru", "government.ru",
        "kremlin.ru", "nalog.gov.ru", "cikrf.ru", "избирком.рф",
        // Банки и платежи
        "sberbank.ru", "sber.ru", "tbank.ru", "tinkoff.ru", "alfabank.ru", "vtb.ru", "gazprombank.ru", "gpb.ru",
        "banki.ru", "pochtabank.ru", "sovcombank.ru", "mkb.ru", "rosbank.ru", "psbank.ru", "rshb.ru",
        "raiffeisen.ru", "nspk.ru", "mironline.ru",
        // Операторы связи
        "mts.ru", "megafon.ru", "beeline.ru", "tele2.ru", "t2.ru", "rt.ru", "rostelecom.ru",
        // Карты, транспорт, почта, новости
        "2gis.ru", "2gis.com", "rzd.ru", "tutu.ru", "pochta.ru", "gismeteo.ru", "rutube.ru",
        "rbc.ru", "lenta.ru", "kp.ru",
    )

    /** Приложение из белого списка: пакет и понятное название. */
    data class PresetApp(val packageName: String, val title: String)

    /**
     * Приложения, которые обычно работают в белых списках и не любят VPN.
     * В списке на экране приложений показываются только установленные.
     * Браузеров здесь нет (Яндекс Браузер, Яндекс с Алисой): через них
     * открываются любые сайты, и исключение увело бы их мимо VPN.
     */
    val PRESET_APPS: List<PresetApp> = listOf(
        PresetApp("ru.sberbankmobile", "СберБанк Онлайн"),
        PresetApp("com.idamob.tinkoff.android", "Т-Банк"),
        PresetApp("ru.alfabank.mobile.android", "Альфа-Банк"),
        PresetApp("ru.vtb24.mobilebanking.android", "ВТБ Онлайн"),
        PresetApp("ru.gazprombank.android.mobilebank.app", "Газпромбанк"),
        PresetApp("ru.raiffeisennews", "Райффайзен Онлайн"),
        PresetApp("ru.nspk.mirpay", "Mir Pay"),
        PresetApp("ru.rostel", "Госуслуги"),
        PresetApp("ru.oneme.app", "MAX"),
        PresetApp("com.vkontakte.android", "ВКонтакте"),
        PresetApp("com.vk.vkvideo", "VK Видео"),
        PresetApp("ru.ok.android", "Одноклассники"),
        PresetApp("ru.mail.mailapp", "Почта Mail.ru"),
        PresetApp("ru.vk.store", "RuStore"),
        PresetApp("ru.yandex.yandexmaps", "Яндекс Карты"),
        PresetApp("ru.yandex.taxi", "Яндекс Go"),
        PresetApp("ru.yandex.music", "Яндекс Музыка"),
        PresetApp("ru.beru.android", "Яндекс Маркет"),
        PresetApp("ru.kinopoisk", "Кинопоиск"),
        PresetApp("com.wildberries.ru", "Wildberries"),
        PresetApp("ru.ozon.app.android", "Ozon"),
        PresetApp("com.avito.android", "Авито"),
        PresetApp("ru.dublgis.dgismobile", "2ГИС"),
        PresetApp("ru.rzd.pass", "РЖД Пассажирам"),
        PresetApp("com.octopod.russianpost.client.android", "Почта России"),
        PresetApp("ru.mts.mymts", "Мой МТС"),
        PresetApp("ru.megafon.mlk", "МегаФон"),
        PresetApp("ru.beeline.services", "Билайн"),
        PresetApp("ru.tele2.mytele2", "Мой T2"),
        PresetApp("ru.rutube.app", "RUTUBE"),
        PresetApp("ru.hh.android", "hh.ru"),
    )

    val PRESET_PACKAGES: Set<String> = PRESET_APPS.map { it.packageName }.toSet()

    /**
     * Разбирает вставленный текст в список доменов: по строке или через
     * запятую, с префиксами Clash (`- +.wb.ru`), схемами и путями.
     */
    fun parseDomains(text: String): List<String> =
        text.split('\n', ',', ';', ' ')
            .mapNotNull { normalize(it) }
            .distinct()

    fun normalize(raw: String): String? {
        var domain = raw.trim().trimStart('-', '•', ' ', '\t').trim('"', '\'')
        domain = domain.substringBefore('#')
        listOf("DOMAIN-SUFFIX,", "DOMAIN,", "domain:", "full:").forEach { prefix ->
            if (domain.startsWith(prefix, ignoreCase = true)) domain = domain.substring(prefix.length)
        }
        domain = domain.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').substringBefore(',')
        domain = domain.trim().removePrefix("+.").removePrefix("*.").trim('.').lowercase()
        if (domain.isEmpty() || !domain.contains('.') || domain.any { it.isWhitespace() }) return null
        return domain
    }
}
