package dev.omalauncher.pp.linux

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Just enough of the D-Bus wire protocol for the launcher to be a client of the guest's session bus:
 * messages of every type, and bodies of any signature, since notification hints carry variants of
 * whatever an app likes. No unix fd passing; the bus never sends fds to a client that didn't ask.
 */
object DBus {
    const val METHOD_CALL = 1
    const val METHOD_RETURN = 2
    const val ERROR = 3
    const val SIGNAL = 4

    const val NO_REPLY_EXPECTED = 0x1

    /** Larger messages are refused; the spec allows 128 MiB, a notification's image is a few hundred KB. */
    const val MAX_MESSAGE = 64 shl 20

    private const val FIELD_PATH = 1
    private const val FIELD_INTERFACE = 2
    private const val FIELD_MEMBER = 3
    private const val FIELD_ERROR_NAME = 4
    private const val FIELD_REPLY_SERIAL = 5
    private const val FIELD_DESTINATION = 6
    private const val FIELD_SENDER = 7
    private const val FIELD_SIGNATURE = 8

    /** One message; [body] holds a value per complete type of [signature] (see [Reader] for the Kotlin types). */
    data class Message(
        val type: Int,
        val serial: Int,
        val flags: Int = 0,
        val path: String? = null,
        val iface: String? = null,
        val member: String? = null,
        val errorName: String? = null,
        val replySerial: Int? = null,
        val destination: String? = null,
        val sender: String? = null,
        val signature: String = "",
        val body: List<Any?> = emptyList(),
    ) {
        val wantsReply get() = type == METHOD_CALL && flags and NO_REPLY_EXPECTED == 0
    }

    /** A `v`: the value and the signature it was sent with. */
    data class Variant(
        val signature: String,
        val value: Any?,
    )

    fun encode(message: Message): ByteArray {
        val body = Writer().apply { write(message.signature, message.body) }.toByteArray()
        val fields = ArrayList<Any?>()
        fun field(code: Int, sig: String, value: Any?) {
            if (value != null) fields.add(listOf(code, Variant(sig, value)))
        }
        field(FIELD_PATH, "o", message.path)
        field(FIELD_INTERFACE, "s", message.iface)
        field(FIELD_MEMBER, "s", message.member)
        field(FIELD_ERROR_NAME, "s", message.errorName)
        field(FIELD_REPLY_SERIAL, "u", message.replySerial)
        field(FIELD_DESTINATION, "s", message.destination)
        field(FIELD_SENDER, "s", message.sender)
        if (message.signature.isNotEmpty()) field(FIELD_SIGNATURE, "g", message.signature)
        val out = Writer()
        out.write(
            "yyyyuua(yv)",
            listOf('l'.code, message.type, message.flags, 1, body.size, message.serial, fields),
        )
        out.align(8)
        return out.toByteArray() + body
    }

    /** Reads one message; null at the end of the stream. */
    fun read(input: InputStream): Message? {
        val fixed = ByteArray(16)
        val data = DataInputStream(input)
        val first = input.read()
        if (first < 0) return null
        fixed[0] = first.toByte()
        data.readFully(fixed, 1, 15)
        val order =
            when (first.toChar()) {
                'l' -> ByteOrder.LITTLE_ENDIAN
                'B' -> ByteOrder.BIG_ENDIAN
                else -> throw IOException("bad endianness byte $first")
            }
        val head = ByteBuffer.wrap(fixed).order(order)
        val bodyLength = head.getInt(4)
        val fieldsLength = head.getInt(12)
        if (bodyLength !in 0..MAX_MESSAGE || fieldsLength !in 0..MAX_MESSAGE) throw IOException("message too large")
        val headerEnd = 16 + fieldsLength
        val bodyStart = (headerEnd + 7) and 7.inv()
        val all = fixed.copyOf(bodyStart + bodyLength)
        data.readFully(all, 16, all.size - 16)
        return decode(all, order, bodyStart)
    }

    private fun decode(
        bytes: ByteArray,
        order: ByteOrder,
        bodyStart: Int,
    ): Message {
        val header = Reader(ByteBuffer.wrap(bytes, 0, bodyStart).order(order)).read("yyyyuua(yv)")
        val fields = HashMap<Int, Any?>()
        @Suppress("UNCHECKED_CAST")
        for (f in header[6] as List<List<Any?>>) fields[f[0] as Int] = (f[1] as Variant).value
        val signature = fields[FIELD_SIGNATURE] as? String ?: ""
        val body = Reader(ByteBuffer.wrap(bytes, bodyStart, bytes.size - bodyStart).slice().order(order)).read(signature)
        return Message(
            type = header[1] as Int,
            flags = header[2] as Int,
            serial = (header[5] as Long).toInt(),
            path = fields[FIELD_PATH] as? String,
            iface = fields[FIELD_INTERFACE] as? String,
            member = fields[FIELD_MEMBER] as? String,
            errorName = fields[FIELD_ERROR_NAME] as? String,
            replySerial = (fields[FIELD_REPLY_SERIAL] as? Long)?.toInt(),
            destination = fields[FIELD_DESTINATION] as? String,
            sender = fields[FIELD_SENDER] as? String,
            signature = signature,
            body = body,
        )
    }

    /** The complete types in [signature]: "susssasa{sv}i" -> s, u, s, s, s, as, a{sv}, i. */
    fun types(signature: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < signature.length) {
            val end = typeEnd(signature, i)
            out += signature.substring(i, end)
            i = end
        }
        return out
    }

    /** Where the complete type starting at [start] ends. */
    private fun typeEnd(
        sig: String,
        start: Int,
    ): Int {
        if (start >= sig.length) throw IOException("bad signature $sig")
        return when (sig[start]) {
            'a' -> typeEnd(sig, start + 1)
            '(', '{' -> {
                val close = if (sig[start] == '(') ')' else '}'
                var i = start + 1
                while (i < sig.length && sig[i] != close) i = typeEnd(sig, i)
                if (i >= sig.length) throw IOException("bad signature $sig")
                i + 1
            }
            else -> start + 1
        }
    }

    private fun alignment(type: Char) =
        when (type) {
            'n', 'q' -> 2
            'b', 'i', 'u', 'h', 's', 'o', 'a' -> 4
            'x', 't', 'd', '(', '{' -> 8
            else -> 1 // y, g, v
        }

    /**
     * Unmarshals values. Kotlin types: y, n, q, i, h -> Int; u, x, t -> Long; b -> Boolean; d -> Double;
     * s, o, g -> String; v -> [Variant]; ay -> ByteArray; a{..} -> Map; other arrays and structs -> List.
     */
    class Reader(
        private val buf: ByteBuffer,
    ) {
        fun read(signature: String): List<Any?> = types(signature).map(::value)

        private fun align(n: Int) {
            val pos = buf.position()
            val padded = (pos + n - 1) / n * n
            if (padded > buf.limit()) throw IOException("truncated message")
            buf.position(padded)
        }

        private fun value(type: String): Any? {
            align(alignment(type[0]))
            return when (type[0]) {
                'y' -> buf.get().toInt() and 0xff
                'b' -> buf.getInt() != 0
                'n' -> buf.getShort().toInt()
                'q' -> buf.getShort().toInt() and 0xffff
                'i', 'h' -> buf.getInt()
                'u' -> buf.getInt().toLong() and 0xffffffffL
                'x', 't' -> buf.getLong()
                'd' -> buf.getDouble()
                's', 'o' -> string(buf.getInt())
                'g' -> string(buf.get().toInt() and 0xff)
                'v' -> {
                    val sig = string(buf.get().toInt() and 0xff)
                    if (types(sig).size != 1) throw IOException("bad variant signature $sig")
                    Variant(sig, value(sig))
                }
                '(' -> types(type.substring(1, type.length - 1)).map(::value)
                'a' -> array(type.substring(1))
                else -> throw IOException("unknown type $type")
            }
        }

        private fun string(length: Int): String {
            if (length < 0 || length + 1 > buf.remaining()) throw IOException("truncated message")
            val bytes = ByteArray(length)
            buf.get(bytes)
            buf.get() // the terminating NUL
            return bytes.decodeToString()
        }

        private fun array(element: String): Any {
            val length = buf.getInt()
            align(alignment(element[0]))
            if (length < 0 || length > buf.remaining()) throw IOException("truncated message")
            val end = buf.position() + length
            if (element == "y") return ByteArray(length).also { buf.get(it) }
            if (element[0] == '{') {
                val (k, v) = types(element.substring(1, element.length - 1))
                val map = LinkedHashMap<Any?, Any?>()
                while (buf.position() < end) {
                    align(8)
                    map[value(k)] = value(v)
                }
                return map
            }
            val list = ArrayList<Any?>()
            while (buf.position() < end) list += value(element)
            return list
        }
    }

    /** Marshals values; takes any Number for the numeric types, and a List or Map for a{..}. */
    class Writer {
        private var bytes = ByteArray(256)
        private var size = 0

        fun toByteArray(): ByteArray = bytes.copyOf(size)

        fun write(
            signature: String,
            values: List<Any?>,
        ) {
            val types = types(signature)
            require(types.size == values.size) { "${values.size} values for signature $signature" }
            types.zip(values).forEach { (t, v) -> value(t, v) }
        }

        fun align(n: Int) {
            while (size % n != 0) put(0)
        }

        private fun ensure(more: Int) {
            if (size + more > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + more))
        }

        private fun put(b: Int) {
            ensure(1)
            bytes[size++] = b.toByte()
        }

        private fun put(b: ByteArray) {
            ensure(b.size)
            b.copyInto(bytes, size)
            size += b.size
        }

        private fun putInt(
            v: Int,
            at: Int = size,
        ) {
            if (at == size) ensure(4)
            for (i in 0 until 4) bytes[at + i] = (v shr (8 * i)).toByte()
            if (at == size) size += 4
        }

        private fun putLong(v: Long) {
            putInt(v.toInt())
            putInt((v ushr 32).toInt())
        }

        private fun putString(
            s: String,
            lengthByte: Boolean,
        ) {
            val b = s.encodeToByteArray()
            if (lengthByte) put(b.size) else putInt(b.size)
            put(b)
            put(0)
        }

        private fun value(
            type: String,
            v: Any?,
        ) {
            align(alignment(type[0]))
            when (type[0]) {
                'y' -> put((v as Number).toInt())
                'b' -> putInt(if (v as Boolean) 1 else 0)
                'n', 'q' -> (v as Number).toInt().let {
                    put(it)
                    put(it shr 8)
                }
                'i', 'u', 'h' -> putInt((v as Number).toInt())
                'x', 't' -> putLong((v as Number).toLong())
                'd' -> putLong((v as Number).toDouble().toRawBits())
                's', 'o' -> putString(v as String, lengthByte = false)
                'g' -> putString(v as String, lengthByte = true)
                'v' -> {
                    v as Variant
                    putString(v.signature, lengthByte = true)
                    value(v.signature, v.value)
                }
                '(' -> write(type.substring(1, type.length - 1), v as List<*>)
                'a' -> array(type.substring(1), v)
                else -> throw IllegalArgumentException("unknown type $type")
            }
        }

        private fun array(
            element: String,
            v: Any?,
        ) {
            // The length is filled in once the elements are written; it doesn't count the padding before them.
            val lengthAt = size
            putInt(0)
            align(alignment(element[0]))
            val start = size
            when {
                element == "y" && v is ByteArray -> put(v)
                element[0] == '{' -> {
                    val (k, t) = types(element.substring(1, element.length - 1))
                    val entries = if (v is Map<*, *>) v.entries.map { it.key to it.value } else (v as List<*>).map { it as Pair<*, *> }
                    for ((key, item) in entries) {
                        align(8)
                        value(k, key)
                        value(t, item)
                    }
                }
                else -> (v as List<*>).forEach { value(element, it) }
            }
            putInt(size - start, at = lengthAt)
        }
    }
}

/**
 * A client connection to a bus over [input] and [output] (a unix socket in the launcher, TCP in tests).
 * [connect] authenticates and says Hello; then one thread calls [read] in a loop, while any thread may [send].
 */
class DBusConnection(
    private val input: InputStream,
    private val output: OutputStream,
) {
    private var serial = 0

    /** The unique name the bus gave us, ":1.42". */
    var uniqueName: String = ""
        private set

    /**
     * ANONYMOUS, the one way in that works under proot: the bus runs as a faked root but the kernel
     * reports the real uid of every peer, so EXTERNAL can't match. Only the app's uid can reach the
     * socket, in its private files.
     */
    fun connect() {
        output.write(0)
        output.write("AUTH ANONYMOUS ${"omalauncher".encodeToByteArray().toHex()}\r\n".encodeToByteArray())
        output.flush()
        val answer = readAuthLine()
        if (!answer.startsWith("OK ")) throw IOException("bus refused authentication: $answer")
        output.write("BEGIN\r\n".encodeToByteArray())
        output.flush()
        val hello = call(BUS_NAME, BUS_PATH, BUS_NAME, "Hello")
        uniqueName = hello.body.firstOrNull() as? String ?: throw IOException("no name from Hello")
    }

    /**
     * Calls a method and waits for its reply, before the [read] loop starts. Signals that arrive
     * meanwhile (NameAcquired) are dropped. An error reply throws.
     */
    fun call(
        destination: String,
        path: String,
        iface: String,
        member: String,
        signature: String = "",
        args: List<Any?> = emptyList(),
    ): DBus.Message {
        val sent = send(DBus.Message(DBus.METHOD_CALL, 0, path = path, iface = iface, member = member, destination = destination, signature = signature, body = args))
        while (true) {
            val m = DBus.read(input) ?: throw IOException("bus closed the connection")
            if (m.replySerial != sent) continue
            if (m.type == DBus.ERROR) throw IOException("${m.errorName}: ${m.body.firstOrNull()}")
            return m
        }
    }

    /** Sends [message] with the next serial, which it returns. */
    fun send(message: DBus.Message): Int =
        synchronized(this) {
            val s = ++serial
            output.write(DBus.encode(message.copy(serial = s)))
            output.flush()
            s
        }

    fun read(): DBus.Message? = DBus.read(input)

    fun reply(
        call: DBus.Message,
        signature: String = "",
        values: List<Any?> = emptyList(),
    ) {
        if (call.wantsReply) send(DBus.Message(DBus.METHOD_RETURN, 0, replySerial = call.serial, destination = call.sender, signature = signature, body = values))
    }

    fun error(
        call: DBus.Message,
        name: String,
        text: String,
    ) {
        if (call.wantsReply) send(DBus.Message(DBus.ERROR, 0, errorName = name, replySerial = call.serial, destination = call.sender, signature = "s", body = listOf(text)))
    }

    fun signal(
        path: String,
        iface: String,
        member: String,
        signature: String,
        values: List<Any?>,
    ) {
        send(DBus.Message(DBus.SIGNAL, 0, path = path, iface = iface, member = member, signature = signature, body = values))
    }

    /** One line of the authentication exchange, without its CRLF. */
    private fun readAuthLine(): String {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("bus closed the connection")
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 4096) throw IOException("authentication line too long")
            line.append(c.toChar())
        }
    }

    companion object {
        const val BUS_NAME = "org.freedesktop.DBus"
        const val BUS_PATH = "/org/freedesktop/DBus"
    }
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
