package com.fear

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * TCP relay server for F.E.A.R. messenger.
 * Ported from client-console/src/server.c — pure relay, never sees plaintext.
 */
class FearServer(private val port: Int, private val listener: ServerListener) {

    interface ServerListener {
        fun onServerStarted(port: Int)
        fun onServerStopped()
        fun onServerError(error: String)
        fun onClientConnected(name: String, room: String)
        fun onClientDisconnected(name: String, room: String)
    }

    private data class ClientSession(
        val socket: Socket,
        var room: String = "",
        var name: String = ""
    )

    private var serverSocket: ServerSocket? = null
    private val clients = Collections.synchronizedList(mutableListOf<ClientSession>())
    @Volatile private var running = false
    private var acceptThread: Thread? = null

    companion object {
        private const val TAG = "FearServer"
        private const val MAX_CLIENTS = 100
    }

    fun start() {
        if (running) return
        running = true
        acceptThread = Thread {
            try {
                val ss = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(port))
                }
                serverSocket = ss
                Log.i(TAG, "Listening on port $port")
                listener.onServerStarted(port)
                acceptLoop(ss)
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Server error: ${e.message}")
                    listener.onServerError(e.message ?: "Unknown error")
                }
            } finally {
                running = false
                listener.onServerStopped()
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        synchronized(clients) {
            for (c in clients) {
                try { c.socket.close() } catch (_: Exception) {}
            }
            clients.clear()
        }
    }

    fun isRunning(): Boolean = running

    /* ── accept loop ─────────────────────────────────────────────── */

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val clientSocket = try {
                ss.accept()
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Accept error: ${e.message}")
                break
            }

            if (clients.size >= MAX_CLIENTS) {
                try { clientSocket.close() } catch (_: Exception) {}
                continue
            }

            val session = ClientSession(clientSocket)
            clients.add(session)
            Log.i(TAG, "New connection (${clients.size} total)")

            Thread { clientHandler(session) }.apply { isDaemon = true; start() }
        }
    }

    /* ── per-client handler ──────────────────────────────────────── */

    private fun clientHandler(session: ClientSession) {
        try {
            while (running && !session.socket.isClosed) {
                val frame = readFrame(session.socket) ?: break

                /* Parse room/name from frame header */
                if (frame.size < 4) break
                val roomLen = Common.readUInt16(frame, 0)
                if (2 + roomLen + 2 > frame.size) break
                val nameLen = Common.readUInt16(frame, 2 + roomLen)
                if (2 + roomLen + 2 + nameLen > frame.size) break

                val frameRoom = String(frame, 2, roomLen)
                val frameName = String(frame, 2 + roomLen + 2, nameLen)

                /* First message: register room */
                if (session.room.isEmpty()) {
                    session.room = frameRoom
                }

                /* First message: register name + check uniqueness */
                if (session.name.isEmpty()) {
                    val duplicate = synchronized(clients) {
                        clients.any { it !== session && it.room == frameRoom && it.name == frameName && it.name.isNotEmpty() }
                    }
                    if (duplicate) {
                        Log.i(TAG, "Rejected duplicate name '$frameName' in room '$frameRoom'")
                        break
                    }
                    session.name = frameName
                    Log.i(TAG, "Registered: name='${session.name}', room='${session.room}'")
                    listener.onClientConnected(session.name, session.room)
                    sendUserList(session.room)
                }

                broadcast(session.room, frame, session.socket)
            }
        } catch (e: Exception) {
            if (running) Log.d(TAG, "Client handler error: ${e.message}")
        } finally {
            val room = session.room
            val name = session.name
            try { session.socket.close() } catch (_: Exception) {}
            clients.remove(session)
            Log.i(TAG, "Client disconnected: '$name' from '$room'")
            if (name.isNotEmpty() && room.isNotEmpty()) {
                listener.onClientDisconnected(name, room)
                sendUserList(room)
            }
        }
    }

    /* ── read one protocol frame ─────────────────────────────────── */

    private fun readFrame(socket: Socket): ByteArray? {
        val inp = socket.getInputStream()

        // [2 room_len]
        val hdr = ByteArray(2)
        if (!recvAll(inp, hdr, 2)) return null
        val roomLen = Common.readUInt16(hdr, 0)
        if (roomLen > Common.MAX_ROOM) return null

        // [room]
        val room = ByteArray(roomLen)
        if (roomLen > 0 && !recvAll(inp, room, roomLen)) return null

        // [2 name_len]
        val nlBuf = ByteArray(2)
        if (!recvAll(inp, nlBuf, 2)) return null
        val nameLen = Common.readUInt16(nlBuf, 0)
        if (nameLen > Common.MAX_NAME) return null

        // [name]
        val name = ByteArray(nameLen)
        if (nameLen > 0 && !recvAll(inp, name, nameLen)) return null

        // [2 nonce_len]
        val nonceLenBuf = ByteArray(2)
        if (!recvAll(inp, nonceLenBuf, 2)) return null
        val nonceLen = Common.readUInt16(nonceLenBuf, 0)
        if (nonceLen != Common.CRYPTO_AEAD_AES256GCM_NPUBBYTES) return null

        // [nonce]
        val nonce = ByteArray(nonceLen)
        if (!recvAll(inp, nonce, nonceLen)) return null

        // [1 type]
        val typeBuf = ByteArray(1)
        if (!recvAll(inp, typeBuf, 1)) return null

        // [4 clen]
        val clenBuf = ByteArray(4)
        if (!recvAll(inp, clenBuf, 4)) return null
        val clen = Common.readUInt32(clenBuf, 0).toInt()
        if (clen > Common.MAX_FRAME) return null

        // [cipher]
        val cipher = ByteArray(clen)
        if (clen > 0 && !recvAll(inp, cipher, clen)) return null

        // Reassemble complete frame
        val total = 2 + roomLen + 2 + nameLen + 2 + nonceLen + 1 + 4 + clen
        val frame = ByteArray(total)
        var w = 0
        System.arraycopy(hdr, 0, frame, w, 2); w += 2
        System.arraycopy(room, 0, frame, w, roomLen); w += roomLen
        System.arraycopy(nlBuf, 0, frame, w, 2); w += 2
        System.arraycopy(name, 0, frame, w, nameLen); w += nameLen
        System.arraycopy(nonceLenBuf, 0, frame, w, 2); w += 2
        System.arraycopy(nonce, 0, frame, w, nonceLen); w += nonceLen
        frame[w] = typeBuf[0]; w += 1
        System.arraycopy(clenBuf, 0, frame, w, 4); w += 4
        System.arraycopy(cipher, 0, frame, w, clen)

        return frame
    }

    private fun recvAll(inp: java.io.InputStream, buf: ByteArray, len: Int): Boolean {
        var received = 0
        while (received < len) {
            val n = inp.read(buf, received, len - received)
            if (n <= 0) return false
            received += n
        }
        return true
    }

    /* ── broadcast frame to room ─────────────────────────────────── */

    private fun broadcast(room: String, frame: ByteArray, fromSocket: Socket) {
        synchronized(clients) {
            val iter = clients.iterator()
            while (iter.hasNext()) {
                val c = iter.next()
                if (c.socket === fromSocket) continue
                if (c.room.isEmpty()) continue
                if (c.room != room) continue

                try {
                    c.socket.getOutputStream().write(frame)
                } catch (e: Exception) {
                    Log.d(TAG, "Send failed, removing client '${c.name}'")
                    try { c.socket.close() } catch (_: Exception) {}
                    iter.remove()
                }
            }
        }
    }

    /* ── send user list to room ──────────────────────────────────── */

    private fun sendUserList(room: String) {
        synchronized(clients) {
            // Collect names in room
            val names = clients.filter { it.room == room && it.name.isNotEmpty() }
            if (names.isEmpty()) return

            // Build payload: [2 count][2 name_len][name]...
            var payloadSize = 2
            for (c in names) {
                payloadSize += 2 + c.name.toByteArray().size
            }
            val payload = ByteArray(payloadSize)
            Common.writeUInt16(payload, 0, names.size)
            var off = 2
            for (c in names) {
                val nameBytes = c.name.toByteArray()
                Common.writeUInt16(payload, off, nameBytes.size)
                off += 2
                System.arraycopy(nameBytes, 0, payload, off, nameBytes.size)
                off += nameBytes.size
            }

            // Build and send frame to all clients in room
            val roomBytes = room.toByteArray()
            val senderBytes = "server".toByteArray()
            val nonceLen = Common.CRYPTO_AEAD_AES256GCM_NPUBBYTES
            val nonce = ByteArray(nonceLen) // zero nonce for service messages

            val frameLen = 2 + roomBytes.size + 2 + senderBytes.size + 2 + nonceLen + 1 + 4 + payloadSize
            val frame = ByteArray(frameLen)
            var w = 0
            Common.writeUInt16(frame, w, roomBytes.size); w += 2
            System.arraycopy(roomBytes, 0, frame, w, roomBytes.size); w += roomBytes.size
            Common.writeUInt16(frame, w, senderBytes.size); w += 2
            System.arraycopy(senderBytes, 0, frame, w, senderBytes.size); w += senderBytes.size
            Common.writeUInt16(frame, w, nonceLen); w += 2
            System.arraycopy(nonce, 0, frame, w, nonceLen); w += nonceLen
            frame[w] = Common.MSG_TYPE_USER_LIST; w += 1
            Common.writeUInt32(frame, w, payloadSize.toLong()); w += 4
            System.arraycopy(payload, 0, frame, w, payloadSize)

            val iter = clients.iterator()
            while (iter.hasNext()) {
                val c = iter.next()
                if (c.room != room || c.name.isEmpty()) continue
                try {
                    c.socket.getOutputStream().write(frame)
                } catch (e: Exception) {
                    Log.d(TAG, "User list send failed, removing '${c.name}'")
                    try { c.socket.close() } catch (_: Exception) {}
                    iter.remove()
                }
            }
        }
    }
}
