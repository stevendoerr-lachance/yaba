package dev.steven.s3control

import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.Gatt
import dev.steven.s3control.ble.GattOverview
import dev.steven.s3control.ble.S2Gatt
import dev.steven.s3control.ble.ScanSpec
import dev.steven.s3control.data.BikeCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** The pure parts of the driver seam (plan B7 steps 3 and 4): scan spec, shared-link match, GATT overview. */
class DriverSeamTest {

    private fun bike(profile: String?, mac: String = "f8:8a:5e:4b:2c:1d") = BikeCredentials(
        id = 1, name = "Test", ownerName = null, mac = mac,
        encryptionKey = "00112233445566778899aabbccddeeff", userKeyId = 1, bleProfile = profile,
    )

    // --- Scan spec: the S3 scan stays exactly what establish() passed before ----------------------

    @Test
    fun s3AndUnknownScanExactlyAsBefore() {
        for (profile in listOf("ELECTRIFIED_2022", "ELECTRIFIED_2021", "electrified_2020", null, "", "MANUAL", "SMARTBIKE_2016")) {
            val b = bike(profile)
            val spec = ScanSpec.forBike(b)
            // Before: gatt.scan(bike.normalizedMac, bike.advertisedNames, …) and alreadyConnected(same two).
            assertEquals(ScanSpec(b.normalizedMac, b.advertisedNames, null), spec)
            assertEquals("F8:8A:5E:4B:2C:1D", spec.mac)
            assertEquals(listOf("ES3-F88A5E4B2C1D", "EX3-F88A5E4B2C1D"), spec.names)
            assertNull(spec.service)
            assertFalse("no service filter, so every result is taken as before", spec.checksResults)
        }
    }

    @Test
    fun s2ScanAddsTheServiceAndChecksEachResult() {
        val b = bike("ELECTRIFIED_2018")
        assertEquals(BikeFamily.S2, b.family)
        val spec = ScanSpec.forBike(b)
        assertEquals(b.normalizedMac, spec.mac)
        assertEquals(listOf("VANMOOF-4b2c1d", "VANMOOF-4B2C1D"), spec.names)
        assertEquals(S2Gatt.SERVICE, spec.service)
        assertTrue(spec.checksResults)
    }

    @Test
    fun acceptsThisBikeOnlyIgnoringCase() {
        val spec = ScanSpec.forBike(bike("ELECTRIFIED_2018"))
        assertTrue(spec.accepts("f8:8a:5e:4b:2c:1d", null) { null })
        assertTrue(spec.accepts("00:11:22:33:44:55", "vanmoof-4b2c1d") { null })
        assertTrue(spec.accepts("00:11:22:33:44:55", "VanMoof-4B2C1D") { null })
        assertTrue(spec.accepts("00:11:22:33:44:55", null) { "VANMOOF-4b2c1d" })
        // Another S2 (or an S1 / SmartBike, same service UUID) nearby.
        assertFalse(spec.accepts("00:11:22:33:44:55", "VANMOOF-000001") { "VANMOOF-000001" })
        assertFalse(spec.accepts(null, null) { null })
    }

    @Test
    fun deviceNameIsOnlyAskedWhenNeeded() {
        val spec = ScanSpec.forBike(bike("ELECTRIFIED_2018"))
        var asked = 0
        assertTrue(spec.accepts("F8:8A:5E:4B:2C:1D", null) { asked++; null })
        assertTrue(spec.accepts(null, "VANMOOF-4B2C1D") { asked++; null })
        assertEquals(0, asked)
        assertFalse(spec.accepts(null, null) { asked++; null })
        assertEquals(1, asked)
    }

    @Test
    fun sharedLinkMatchIsExactForS3AndCaselessForS2() {
        val spec = ScanSpec.forBike(bike("ELECTRIFIED_2022"))
        assertTrue(spec.matchesAddress("F8:8A:5E:4B:2C:1D"))
        assertTrue(spec.matchesAddress("f8:8a:5e:4b:2c:1d"))
        assertTrue(spec.matchesName("ES3-F88A5E4B2C1D"))
        assertTrue(spec.matchesName("EX3-F88A5E4B2C1D"))
        // As before (`it in names`): the S3 names compare exactly.
        assertFalse(spec.matchesName("es3-f88a5e4b2c1d"))
        assertFalse(spec.matchesName("EX3-f88a5e4b2c1d"))
        assertFalse(spec.matchesName("ES3-000000000000"))
        assertFalse(spec.matchesName(null))
        assertFalse(spec.matchesAddress(null))
        assertFalse(spec.matchesAddress("00:11:22:33:44:55"))

        // The S2 spelling is unverified: any case.
        val s2 = ScanSpec.forBike(bike("ELECTRIFIED_2018"))
        assertTrue(s2.matchesName("VanMoof-4b2C1d"))
        assertFalse(s2.matchesName("ES3-F88A5E4B2C1D"))
    }

    @Test
    fun tentativeS2ProfileAlsoScansForTheS3Names() {
        val b = bike("electrified_2019")
        assertEquals(BikeFamily.S2, b.family)
        assertTrue(BikeFamily.isTentative(b.bleProfile))
        assertFalse(BikeFamily.isTentative("ELECTRIFIED_2018"))
        assertFalse(BikeFamily.isTentative(null))
        val spec = ScanSpec.forBike(b)
        assertEquals(listOf("VANMOOF-4b2c1d", "VANMOOF-4B2C1D", "ES3-F88A5E4B2C1D", "EX3-F88A5E4B2C1D"), spec.names)
        assertEquals(S2Gatt.SERVICE, spec.service)
        // Everything the S3 scan took is still taken: by address or by its ES3-/EX3- name.
        val s3 = ScanSpec.forBike(bike("ELECTRIFIED_2022"))
        assertTrue(spec.names.containsAll(s3.names))
        assertTrue(spec.accepts("F8:8A:5E:4B:2C:1D", null) { null })
        assertTrue(spec.accepts("00:11:22:33:44:55", "ES3-F88A5E4B2C1D") { null })
        assertTrue(spec.accepts("00:11:22:33:44:55", "VANMOOF-4b2c1d") { null })
        assertFalse(spec.accepts("00:11:22:33:44:55", "VANMOOF-000001") { null })
    }

    // --- GATT overview (read-only, S2 test mode) ----------------------------------------------

    @Test
    fun propertyNames() {
        assertEquals("read,notify", GattOverview.properties(0x02 or 0x10))
        assertEquals("write-no-response,write", GattOverview.properties(0x04 or 0x08))
        assertEquals("", GattOverview.properties(0))
    }

    @Test
    fun overviewListsLayoutLabelsAndForbiddenUuids() {
        val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val services = listOf(
            GattOverview.Service(
                S2Gatt.SERVICE,
                listOf(
                    GattOverview.Characteristic(S2Gatt.CHALLENGE, 0x02),
                    GattOverview.Characteristic(S2Gatt.KEY_CHANGE, 0x08),
                    GattOverview.Characteristic(S2Gatt.PARAMETERS, 0x12, listOf(cccd)),
                ),
            ),
            GattOverview.Service(S2Gatt.OAD_SERVICE, emptyList()),
        )
        val labels = mapOf(S2Gatt.CHALLENGE to "Challenge", S2Gatt.PARAMETERS to "Status block (S2)")
        val text = GattOverview.format(services) { labels[it] }
        val lines = text.lines()
        assertEquals("services=2 characteristics=3", lines[0])
        assertEquals("S ${S2Gatt.SERVICE}", lines[1])
        assertEquals("  C ${S2Gatt.CHALLENGE} [read] Challenge", lines[2])
        assertEquals("  C ${S2Gatt.KEY_CHANGE} [write] [never used]", lines[3])
        assertEquals("  C ${S2Gatt.PARAMETERS} [read,notify] Status block (S2)", lines[4])
        assertEquals("    D $cccd", lines[5])
        assertEquals("S ${S2Gatt.OAD_SERVICE} [never used]", lines[6])
        assertEquals(7, lines.size)
    }

    @Test
    fun forbiddenCharacteristicIsMarkedOnceNextToItsLabel() {
        val services = listOf(GattOverview.Service(S2Gatt.SERVICE, listOf(GattOverview.Characteristic(S2Gatt.KEY_CHANGE, 0x08))))
        // The real label (strings_s2.xml core_char_s2_key_change) no longer says "never used" itself.
        val text = GattOverview.format(services) { if (it == S2Gatt.KEY_CHANGE) "Key change (S2)" else null }
        assertEquals("  C ${S2Gatt.KEY_CHANGE} [write] Key change (S2) [never used]", text.lines()[2])
        assertEquals(1, Regex("never used").findAll(text).count())
    }

    @Test
    fun s2CharacteristicsHaveLabelsAndS3OnesStayWithGatt() {
        for (id in listOf(S2Gatt.CHALLENGE, S2Gatt.FUNCTIONS, S2Gatt.PARAMETERS, S2Gatt.KEY_CHANGE) + S2Gatt.deviceInfo) {
            assertNotNull("label for $id", S2Gatt.labelRes(id))
        }
        assertNull(S2Gatt.labelRes(Gatt.LOCK_STATE.id))
        assertNull(S2Gatt.labelRes(S2Gatt.SERVICE))
    }
}
