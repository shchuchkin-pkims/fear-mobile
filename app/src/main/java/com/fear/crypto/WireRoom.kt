package com.fear.crypto

/**
 * Имя комнаты, каким его видит ретранслятор.
 *
 * На проводе едет не «general», а хеш от него: оператору незачем читать в
 * своём журнале, кто в какой комнате сидит, а маршрутизировать по метке он
 * может ровно так же. Заодно исчезает приставка «pm:», по которой личные
 * комнаты отличались от общих с одного взгляда.
 *
 * Зеркалит identity_wire_room из C-библиотеки: разойдясь, телефон и ПК
 * оказались бы в разных комнатах и молча не видели друг друга.
 *
 * Честно о пределе: хеш без секрета, и «general» подбирается по словарю. Это
 * защита от чтения журнала, а не от оператора, который целенаправленно ищет.
 * Скрыть название полностью мешает вход по имени: входящий ещё не знает ключа
 * комнаты, а значит и вывести метку под ним не может.
 */
object WireRoom {

    private const val CTX = "fear.room.v1"

    fun of(roomName: String, hasher: KeyedHash = SodiumKeyedHash): String {
        val ctx = CTX.toByteArray(Charsets.US_ASCII)
        val name = roomName.toByteArray(Charsets.UTF_8)
        val digest = hasher.blake2b(ctx + name, ByteArray(0), 16)
        val b64 = android.util.Base64.encodeToString(
            digest,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or
                android.util.Base64.NO_PADDING,
        )
        return "r:$b64"
    }
}
