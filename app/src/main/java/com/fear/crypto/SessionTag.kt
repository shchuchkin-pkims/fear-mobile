package com.fear.crypto

import java.security.SecureRandom

/**
 * Метка, которой участник представляется ретранслятору.
 *
 * Раньше в этом поле кадра ехало отображаемое имя, и оператор читал в своём
 * журнале готовый список: кто, в какой комнате, с какого адреса. Имена
 * устойчивы между сеансами, поэтому по ним складывался и граф знакомств -
 * кто с кем всегда оказывается в одной комнате.
 *
 * Теперь едет метка: 16 случайных байт, новые на каждое подключение.
 * Ретранслятору её хватает ровно на то, что он и делает, - отличать
 * соединения друг от друга и закреплять кадр за отправителем. Связать два
 * сеанса одного человека по ней нельзя: общего между ними нет.
 *
 * Зеркалит identity_session_tag и identity_announce_signed_bytes из
 * C-библиотеки. Разойдись подписи хоть на байт - телефон и ПК перестали бы
 * узнавать анонсы друг друга, и каждый показывал бы собеседника
 * неизвестным, ничего при этом не сломав вслух.
 */
object SessionTag {

    private const val ANNOUNCE_CTX = "fear.announce.v2"

    /** Длина метки в знаках base64url: 16 байт без выравнивания. */
    const val LENGTH = 22

    private val rng = SecureRandom()

    /** Новая метка на новое подключение. */
    fun random(): String {
        val raw = ByteArray(16)
        rng.nextBytes(raw)
        return android.util.Base64.encodeToString(
            raw,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
                android.util.Base64.NO_PADDING,
        )
    }

    /**
     * Что подписывает анонс личности: контекст, метка сессии, имя.
     *
     * Подпись только над именем позволила бы взять чужой анонс и повторить
     * его под своей меткой - имя досталось бы вместе с ним. Метка внутри
     * подписи это закрывает: она привязывает «я зовусь так» к «я вот это
     * соединение».
     *
     * Метка постоянной длины, поэтому склейка читается однозначно и без
     * разделителя.
     */
    fun announceSignedBytes(sessionTag: String, displayName: String): ByteArray =
        ANNOUNCE_CTX.toByteArray(Charsets.US_ASCII) +
            sessionTag.toByteArray(Charsets.US_ASCII) +
            displayName.toByteArray(Charsets.UTF_8)
}
