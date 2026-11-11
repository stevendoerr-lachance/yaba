package dev.steven.s3control.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.annotation.StringRes
import androidx.core.location.LocationManagerCompat
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import dev.steven.s3control.data.BikeCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

class GattException(message: String, val status: Int? = null) : Exception(message)

/**
 * Thin coroutine layer over android.bluetooth: one GATT operation at a time,
 * each with a timeout, and API 33+ and legacy callback paths both handled.
 * Callers must hold BLUETOOTH_CONNECT / BLUETOOTH_SCAN (API 31+) before calling in.
 */
@SuppressLint("MissingPermission")
class GattClient(private val context: Context, private val onAdapterBackOn: () -> Unit = {}) {

    enum class Link { Idle, Scanning, Connecting, Discovering, Ready, Disconnected }

    class Notification(val characteristic: UUID, val value: ByteArray)

    private class OpResult(val status: Int, val value: ByteArray?)

    private enum class Kind { READ, WRITE, DESCRIPTOR, MTU }

    /** The one operation in flight; callbacks only complete it when kind and characteristic match. */
    private class Pending(val kind: Kind, val uuid: UUID?, val result: CompletableDeferred<OpResult>)

    private val _link = MutableStateFlow(Link.Idle)
    val link: StateFlow<Link> = _link.asStateFlow()

    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 256)
    val notifications: SharedFlow<Notification> = _notifications.asSharedFlow()

    private val opLock = Mutex()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var pendingOp: Pending? = null
    @Volatile private var connectWait: CompletableDeferred<Int>? = null
    @Volatile private var discoverWait: CompletableDeferred<Int>? = null
    @Volatile var mtu: Int = 23
        private set

    val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val isReady: Boolean get() = _link.value == Link.Ready && gatt != null

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    /** Below Android 12, scanning silently finds nothing while system Location is off. */
    val canScan: Boolean
        get() = Build.VERSION.SDK_INT >= 31 ||
            context.getSystemService(LocationManager::class.java)?.let { LocationManagerCompat.isLocationEnabled(it) } == true

    init {
        // Bluetooth switched off: no onConnectionStateChange arrives, so tear the link down ourselves.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> if (gatt != null || _link.value != Link.Idle) close()
                    BluetoothAdapter.STATE_ON -> onAdapterBackOn()
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Scans for the bike by address or advertised name (and, for [ScanSpec.service], the service UUID); null on
     * timeout. A spec with a service filter takes only a result that [ScanSpec.accepts]: that filter matches
     * every bike of the generation nearby. Without one, every result already matched the address or a name.
     */
    suspend fun scan(spec: ScanSpec, timeoutMs: Long): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: throw GattException(I18n.get(R.string.core_error_bluetooth_off))
        val filters = buildList {
            if (BluetoothAdapter.checkBluetoothAddress(spec.mac)) add(ScanFilter.Builder().setDeviceAddress(spec.mac).build())
            spec.names.forEach { add(ScanFilter.Builder().setDeviceName(it).build()) }
            spec.service?.let { add(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()) }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        _link.value = Link.Scanning
        var callback: ScanCallback? = null
        val found = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        if (spec.checksResults && !accepts(spec, result)) return
                        if (cont.isActive) cont.resume(result.device)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                callback = cb
                scanner.startScan(filters, settings, cb)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
            }
        }
        callback?.let { runCatching { scanner.stopScan(it) } }
        if (found == null && _link.value == Link.Scanning) _link.value = Link.Idle
        return found
    }

    private fun accepts(spec: ScanSpec, result: ScanResult): Boolean =
        spec.accepts(result.device?.address, result.scanRecord?.deviceName) { runCatching { result.device?.name }.getOrNull() }

    /**
     * A bike this phone is already linked to over GATT, e.g. because the VanMoof app holds the
     * connection in the background. A connected bike stops advertising, so a scan would miss it,
     * but Android shares the existing link with every app on the phone. Names compare as [ScanSpec.matchesName].
     */
    fun alreadyConnected(spec: ScanSpec): BluetoothDevice? {
        val bm = context.getSystemService(BluetoothManager::class.java) ?: return null
        val devices = runCatching { bm.getConnectedDevices(BluetoothProfile.GATT) }.getOrNull() ?: return null
        return devices.firstOrNull { d ->
            spec.matchesAddress(d.address) || spec.matchesName(runCatching { d.name }.getOrNull())
        }
    }

    fun deviceFor(mac: String): BluetoothDevice? =
        if (BluetoothAdapter.checkBluetoothAddress(mac)) adapter?.getRemoteDevice(mac) else null

    suspend fun connect(device: BluetoothDevice, timeoutMs: Long = 15_000) {
        close()
        _link.value = Link.Connecting
        val wait = CompletableDeferred<Int>()
        connectWait = wait
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw GattException(I18n.get(R.string.core_error_connect_gatt_null))
        gatt = g
        val status = withTimeoutOrNull(timeoutMs) { wait.await() }
        connectWait = null
        if (status != BluetoothGatt.GATT_SUCCESS) {
            close()
            throw GattException(
                when (status) {
                    null, 147 -> I18n.get(R.string.core_error_no_answer)
                    133 -> I18n.get(R.string.core_error_connect_133)
                    else -> I18n.get(R.string.core_error_connect_status, status.toString())
                },
                status,
            )
        }
        _link.value = Link.Discovering
        // Some stacks drop discovery issued in the same tick as the connect callback.
        delay(350)
        val discovered = CompletableDeferred<Int>()
        discoverWait = discovered
        mtu = 23
        if (!withContext(Dispatchers.Main) { g.discoverServices() }) {
            close(); throw GattException(I18n.get(R.string.core_error_services))
        }
        val dStatus = withTimeoutOrNull(12_000) { discovered.await() }
        discoverWait = null
        if (dStatus != BluetoothGatt.GATT_SUCCESS) {
            close(); throw GattException(I18n.get(R.string.core_error_services_status, dStatus.toString()), dStatus)
        }
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        _link.value = Link.Ready
    }

    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
        gatt?.getService(service)?.getCharacteristic(characteristic) != null

    fun properties(service: UUID, characteristic: UUID): Int =
        gatt?.getService(service)?.getCharacteristic(characteristic)?.properties ?: 0

    suspend fun read(service: UUID, characteristic: UUID, timeoutMs: Long = 6_000): ByteArray {
        val r = op(timeoutMs, R.string.core_op_read, Kind.READ, characteristic) { g ->
            g.readCharacteristic(g.characteristic(service, characteristic))
        }
        return r.value ?: ByteArray(0)
    }

    suspend fun write(
        service: UUID,
        characteristic: UUID,
        value: ByteArray,
        withResponse: Boolean = true,
        timeoutMs: Long = 6_000,
    ) {
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        op(timeoutMs, R.string.core_op_write, Kind.WRITE, characteristic) { g ->
            val ch = g.characteristic(service, characteristic)
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.writeType = type
                @Suppress("DEPRECATION")
                ch.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        }
    }

    suspend fun enableNotifications(service: UUID, characteristic: UUID) {
        val g = gatt ?: throw GattException(I18n.get(R.string.core_error_not_connected))
        val ch = g.characteristic(service, characteristic)
        val props = ch.properties
        val value = when {
            props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> return
        }
        if (!g.setCharacteristicNotification(ch, true)) return
        val cccd = ch.getDescriptor(CCCD) ?: return
        op(6_000, R.string.core_op_notify, Kind.DESCRIPTOR, characteristic) { gg ->
            if (Build.VERSION.SDK_INT >= 33) {
                gg.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                gg.writeDescriptor(cccd)
            }
        }
    }

    /** Returns the negotiated ATT MTU; 23 if the peer never answers. Android 14+ always asks for 517. */
    suspend fun requestMtu(wanted: Int = 517): Int {
        runCatching { op(5_000, R.string.core_op_mtu, Kind.MTU, null) { g -> g.requestMtu(wanted) } }
        return mtu
    }

    /** Drops Android's GATT cache for this device (hidden API, reachable by reflection). */
    fun refreshCache(): Boolean {
        val g = gatt ?: return false
        return runCatching { g.javaClass.getMethod("refresh").invoke(g) as Boolean }.getOrDefault(false)
    }

    /** Re-runs service discovery, e.g. after [refreshCache]. */
    suspend fun rediscover(): Boolean {
        val g = gatt ?: return false
        val discovered = CompletableDeferred<Int>()
        discoverWait = discovered
        val started = withContext(Dispatchers.Main) { g.discoverServices() }
        if (!started) return false
        val status = withTimeoutOrNull(12_000) { discovered.await() }
        discoverWait = null
        return status == BluetoothGatt.GATT_SUCCESS
    }

    fun hasService(service: UUID): Boolean = gatt?.getService(service) != null

    /**
     * Read-only overview of the discovered GATT layout: services, characteristics with their properties, and
     * descriptors (S2 test mode, "Copy GATT overview"). Nothing is read or written. Null while not connected.
     */
    fun describeServices(): String? {
        val g = gatt ?: return null
        val services = g.services.orEmpty().map { s ->
            GattOverview.Service(
                s.uuid,
                s.characteristics.orEmpty().map { c ->
                    GattOverview.Characteristic(c.uuid, c.properties, c.descriptors.orEmpty().map { it.uuid })
                },
            )
        }
        return GattOverview.format(services) { charLabel(it) }
    }

    fun close() {
        val g = gatt
        gatt = null
        pendingOp?.result?.complete(OpResult(DISCONNECTED_STATUS, null))
        connectWait?.complete(DISCONNECTED_STATUS)
        discoverWait?.complete(DISCONNECTED_STATUS)
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        if (_link.value != Link.Idle) _link.value = Link.Disconnected
    }

    private suspend fun op(
        timeoutMs: Long,
        @StringRes whatRes: Int,
        kind: Kind,
        uuid: UUID?,
        start: (BluetoothGatt) -> Boolean,
    ): OpResult = opLock.withLock {
        val g = gatt ?: throw GattException(I18n.get(R.string.core_error_not_connected))
        val wait = CompletableDeferred<OpResult>()
        pendingOp = Pending(kind, uuid, wait)
        try {
            if (!start(g)) throw GattException(I18n.get(R.string.core_error_op_not_started, I18n.get(whatRes)))
            val r = withTimeoutOrNull(timeoutMs) { wait.await() }
                ?: throw GattException(I18n.get(R.string.core_error_op_timeout, I18n.get(whatRes)))
            if (r.status == DISCONNECTED_STATUS) throw GattException(I18n.get(R.string.core_error_link_lost))
            if (r.status != BluetoothGatt.GATT_SUCCESS) {
                throw GattException(I18n.get(R.string.core_error_op_failed, I18n.get(whatRes), r.status), r.status)
            }
            r
        } finally {
            pendingOp = null
        }
    }

    private fun BluetoothGatt.characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic =
        getService(service)?.getCharacteristic(characteristic)
            ?: throw GattException(I18n.get(R.string.core_error_missing_char, charLabel(characteristic) ?: characteristic.toString()))

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectWait?.complete(BluetoothGatt.GATT_SUCCESS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                connectWait?.complete(if (status == BluetoothGatt.GATT_SUCCESS) DISCONNECTED_STATUS else status)
                discoverWait?.complete(DISCONNECTED_STATUS)
                pendingOp?.result?.complete(OpResult(DISCONNECTED_STATUS, null))
                gatt = null
                runCatching { g.close() }
                _link.value = Link.Disconnected
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            discoverWait?.complete(status)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            complete(Kind.READ, characteristic.uuid, OpResult(status, value))
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            complete(Kind.READ, characteristic.uuid, OpResult(status, characteristic.value?.copyOf()))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            complete(Kind.WRITE, characteristic.uuid, OpResult(status, null))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            complete(Kind.DESCRIPTOR, descriptor.characteristic?.uuid, OpResult(status, null))
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@GattClient.mtu = mtu
            complete(Kind.MTU, null, OpResult(status, null))
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            _notifications.tryEmit(Notification(characteristic.uuid, value))
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            _notifications.tryEmit(Notification(characteristic.uuid, characteristic.value?.copyOf() ?: ByteArray(0)))
        }
    }

    /** Completes the pending op only if this callback belongs to it; stale or foreign callbacks are ignored. */
    private fun complete(kind: Kind, uuid: UUID?, result: OpResult) {
        val p = pendingOp ?: return
        if (p.kind != kind) return
        if (p.uuid != null && uuid != null && p.uuid != uuid) return
        p.result.complete(result)
    }

    companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DISCONNECTED_STATUS = -10_000

        /** The localised name of an S3 ([Gatt]) or S2 ([S2Gatt.labelRes]) characteristic; null if unknown. */
        fun charLabel(id: UUID): String? = Gatt.find(id)?.label ?: S2Gatt.labelRes(id)?.let { I18n.get(it) }
    }
}

/**
 * What a scan and the shared-link check look for (the S2 plan B3): the bike's address, its advertised
 * names and, for a family whose service UUID other bikes share, that service. Pure, so it is unit-tested.
 */
data class ScanSpec(val mac: String, val names: List<String>, val service: UUID? = null) {

    /** A service filter matches every S1/S2/SmartBike nearby, so each result must be checked ([accepts]). */
    val checksResults: Boolean get() = service != null

    fun matchesAddress(address: String?): Boolean = address != null && address.equals(mac, ignoreCase = true)

    /**
     * One of [names]. Exact, as YABA always compared the S3 names; ignoring case only for a spec with a service
     * filter (S2), whose advertised spelling is unverified (plan B3).
     */
    fun matchesName(name: String?): Boolean =
        name != null && names.any { if (service == null) it == name else it.equals(name, ignoreCase = true) }

    /** This bike: the address, the advertised name or the device name matches ([matchesName]). [deviceName] is
     *  asked last, only when needed: reading it can need a permission. */
    fun accepts(address: String?, recordName: String?, deviceName: () -> String?): Boolean =
        matchesAddress(address) || matchesName(recordName) || matchesName(deviceName())

    companion object {
        /**
         * S3 and every other family: address plus the ES3-/EX3- names, no service filter, exactly the scan YABA
         * always ran. S2: the VANMOOF- names in both cases plus the S2 service, with each result checked; a
         * profile that only probably means S2 ([BikeFamily.isTentative]) adds the S3 names, so an S3 with it is
         * still found. A bike with no profile (UNKNOWN) keeps the S3 scan: plan B3 would add the S2 names and
         * the service there, which changes the scan for every profile-less S3 (the owner's decision).
         */
        fun forBike(bike: BikeCredentials): ScanSpec = ScanSpec(
            mac = bike.normalizedMac,
            names = bike.advertisedNames,
            service = if (bike.family == BikeFamily.S2) S2Gatt.SERVICE else null,
        )
    }
}

/** Formats a discovered GATT layout as plain text for the S2 test notes. Pure, so it is unit-tested. */
object GattOverview {
    class Characteristic(val id: UUID, val properties: Int, val descriptors: List<UUID> = emptyList())

    class Service(val id: UUID, val characteristics: List<Characteristic>)

    /** BluetoothGattCharacteristic.PROPERTY_* bits, lowest first. */
    private val propertyNames = listOf(
        0x01 to "broadcast", 0x02 to "read", 0x04 to "write-no-response", 0x08 to "write",
        0x10 to "notify", 0x20 to "indicate", 0x40 to "signed-write", 0x80 to "extended",
    )

    fun properties(bits: Int): String = propertyNames.filter { (bit, _) -> bits and bit != 0 }.joinToString(",") { it.second }

    /**
     * One line per service, characteristic and descriptor, with the property names and [label] where known.
     * UUIDs YABA must never touch ([S2Gatt.forbidden]) are marked.
     */
    fun format(services: List<Service>, label: (UUID) -> String?): String = buildString {
        append("services=").append(services.size)
            .append(" characteristics=").append(services.sumOf { it.characteristics.size }).append('\n')
        for (s in services) {
            append("S ").append(s.id)
            if (s.id in S2Gatt.forbidden) append(" [never used]")
            append('\n')
            for (c in s.characteristics) {
                append("  C ").append(c.id).append(" [").append(properties(c.properties)).append(']')
                label(c.id)?.let { append(' ').append(it) }
                if (c.id in S2Gatt.forbidden) append(" [never used]")
                append('\n')
                for (d in c.descriptors) append("    D ").append(d).append('\n')
            }
        }
    }.trimEnd()
}
