package dev.steven.s3control.audio

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.Properties
import java.util.zip.CRC32

/**
 * What the bike's custom bell slot (the one "Nebelhorn" plays) holds, as far as YABA sent it: the bike cannot
 * read a sound back, so this is the only record of it. Kept per bike (by MAC), replaced by every complete upload
 * and dropped when a transfer broke off after the bike had erased the slot.
 */
class BellSlotRecord(
    /** The name the owner saw when sending: the file name or the bundled bell's name. */
    val name: String,
    /** [BundledBell] enum name when a bundled bell was sent, else null. */
    val bundledId: String?,
    val seconds: Float,
    /** Waveform columns (7-bit masks, top row = bit 0), as the matrix draws them; null if unknown. */
    val wave: IntArray?,
    /** When the transfer completed (epoch ms). */
    val sentAt: Long,
    /** The copy of the file on this phone ([BellSlotStore.kept]), null if it could not be kept. */
    val keptId: String? = null,
) {
    fun toProperties(): Properties = Properties().apply {
        setProperty("name", name)
        bundledId?.let { setProperty("bundled", it) }
        setProperty("seconds", seconds.toString())
        wave?.let { w -> setProperty("wave", w.joinToString(",")) }
        setProperty("sentAt", sentAt.toString())
        keptId?.let { setProperty("kept", it) }
    }

    override fun equals(other: Any?): Boolean =
        other is BellSlotRecord && name == other.name && bundledId == other.bundledId && seconds == other.seconds &&
            (wave?.contentEquals(other.wave) ?: (other.wave == null)) && sentAt == other.sentAt && keptId == other.keptId

    override fun hashCode(): Int =
        listOf(name, bundledId, seconds, wave?.contentHashCode(), sentAt, keptId).hashCode()

    companion object {
        /** Null when the properties do not describe a record (missing name or date). */
        fun fromProperties(p: Properties): BellSlotRecord? {
            val name = p.getProperty("name")?.takeIf { it.isNotBlank() } ?: return null
            val sentAt = p.getProperty("sentAt")?.toLongOrNull() ?: return null
            val wave = p.getProperty("wave")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                ?.takeIf { it.isNotEmpty() }?.toIntArray()
            return BellSlotRecord(
                name = name,
                bundledId = p.getProperty("bundled")?.takeIf { it.isNotBlank() },
                seconds = p.getProperty("seconds")?.toFloatOrNull() ?: 0f,
                wave = wave,
                sentAt = sentAt,
                keptId = p.getProperty("kept")?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** A bell file YABA sent before and kept on the phone, ready to be sent again. */
class KeptBell(
    val id: String,
    val name: String,
    val seconds: Float,
    val bundledId: String?,
    val wave: IntArray?,
    /** Last complete transfer of this file (epoch ms). */
    val sentAt: Long,
    val file: File,
)

/**
 * `files/bellslot/bikes/<mac>.properties` holds each bike's [BellSlotRecord]; `files/bellslot/kept/<crc>.vms`
 * (plus a properties file) keeps every bell file that arrived in full, the newest [MAX_KEPT], so an earlier bell
 * can go back to the bike without picking the file again. All writes go through a temporary file and a rename.
 */
class BellSlotStore(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "bellslot"))

    private val bikes get() = File(root, "bikes")
    private val keptDir get() = File(root, "kept")

    private fun bikeFile(mac: String) = File(bikes, macKey(mac) + ".properties")

    fun load(mac: String): BellSlotRecord? = BellSlotRecord.fromProperties(readProps(bikeFile(mac)))

    /**
     * Records [record] as what [mac]'s slot holds. [file] (the bike file that was sent) is kept on the phone;
     * the returned record carries its id. A file that cannot be kept does not stop the record.
     */
    fun save(mac: String, record: BellSlotRecord, file: ByteArray?): BellSlotRecord {
        val keptId = file?.let { runCatching { keep(it, record) }.getOrNull() }
        val saved = BellSlotRecord(record.name, record.bundledId, record.seconds, record.wave, record.sentAt, keptId ?: record.keptId)
        if (!bikes.isDirectory && !bikes.mkdirs()) error("cannot create ${bikes.path}")
        writeProps(bikeFile(mac), saved.toProperties())
        runCatching { prune() }
        return saved
    }

    /** The slot no longer holds what the record says (a transfer broke off after the erase). */
    fun clear(mac: String) {
        bikeFile(mac).delete()
    }

    /** Kept bell files, newest first. */
    fun kept(): List<KeptBell> =
        keptDir.listFiles { f -> f.name.endsWith(".vms") }.orEmpty().mapNotNull { f ->
            val id = f.nameWithoutExtension
            val meta = readProps(File(keptDir, "$id.properties"))
            val name = meta.getProperty("name") ?: return@mapNotNull null
            KeptBell(
                id = id,
                name = name,
                seconds = meta.getProperty("seconds")?.toFloatOrNull() ?: 0f,
                bundledId = meta.getProperty("bundled")?.takeIf { it.isNotBlank() },
                wave = meta.getProperty("wave")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
                    ?.takeIf { it.isNotEmpty() }?.toIntArray(),
                sentAt = meta.getProperty("sentAt")?.toLongOrNull() ?: f.lastModified(),
                file = f,
            )
        }.sortedByDescending { it.sentAt }

    private fun keep(bytes: ByteArray, record: BellSlotRecord): String {
        if (!keptDir.isDirectory && !keptDir.mkdirs()) error("cannot create ${keptDir.path}")
        val id = String.format(Locale.ROOT, "%08x", CRC32().apply { update(bytes) }.value)
        val file = File(keptDir, "$id.vms")
        if (!file.isFile || file.length() != bytes.size.toLong()) writeAtomically(file, bytes)
        val meta = Properties().apply {
            setProperty("name", record.name)
            setProperty("seconds", record.seconds.toString())
            record.bundledId?.let { setProperty("bundled", it) }
            record.wave?.let { w -> setProperty("wave", w.joinToString(",")) }
            setProperty("sentAt", record.sentAt.toString())
        }
        writeProps(File(keptDir, "$id.properties"), meta)
        return id
    }

    /** Keeps the newest [MAX_KEPT] files; a file a bike's record points to always stays. */
    private fun prune() {
        val inUse = bikes.listFiles { f -> f.name.endsWith(".properties") }.orEmpty()
            .mapNotNull { BellSlotRecord.fromProperties(readProps(it))?.keptId }.toSet()
        kept().drop(MAX_KEPT).filter { it.id !in inUse }.forEach { k ->
            k.file.delete()
            File(keptDir, "${k.id}.properties").delete()
        }
    }

    private fun readProps(f: File): Properties = Properties().apply {
        if (f.isFile) runCatching { f.inputStream().use { load(it) } }
    }

    private fun writeProps(f: File, p: Properties) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { p.store(it, null) }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) error("cannot write ${f.path}")
        }
    }

    private fun writeAtomically(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) error("cannot write ${f.path}")
        }
    }

    companion object {
        const val MAX_KEPT = 10

        /** "F8:8A:5E:01:02:03" and "f88a5e010203" name the same file. */
        fun macKey(mac: String): String = mac.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)

        /** Sample rate from a VM_SOUND bell file's WAV header (byte 52), 0 when the file is too short. */
        fun sampleRate(file: ByteArray): Int {
            if (file.size < 56) return 0
            return (file[52].toInt() and 0xFF) or ((file[53].toInt() and 0xFF) shl 8) or
                ((file[54].toInt() and 0xFF) shl 16) or ((file[55].toInt() and 0xFF) shl 24)
        }
    }
}
