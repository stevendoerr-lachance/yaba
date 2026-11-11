package dev.steven.s3control

import dev.steven.s3control.ble.SpeedPlus
import dev.steven.s3control.ble.SpeedPlus.WHEEL_24
import dev.steven.s3control.ble.SpeedPlus.WHEEL_28
import dev.steven.s3control.ble.SpeedPlusView
import dev.steven.s3control.ble.WheelStock
import dev.steven.s3control.ble.WheelStock.Source
import dev.steven.s3control.data.BikeCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedPlusTest {
    private fun bike(model: String) = BikeCredentials(
        id = 1, name = "Test", ownerName = "", mac = "74:D2:85:00:00:01",
        encryptionKey = "00000000000000000000000000000000", userKeyId = 1,
        modelName = model, colorPrimary = "", fromAccount = false,
    )

    @Test fun factorIsTheFirmwareNumeratorRatio() {
        // sensor.c: 0x21660 (28") over 0x1C200 (24").
        assertEquals(0x21660 / 0x1C200.toFloat(), SpeedPlus.FACTOR, 1e-6f)
    }

    @Test fun onlyA28InchBikeOn24ReadsLow() {
        assertEquals(SpeedPlus.FACTOR, SpeedPlus.factor(SpeedPlus.WHEEL_28, SpeedPlus.WHEEL_24), 0f)
        assertEquals(1f, SpeedPlus.factor(SpeedPlus.WHEEL_28, SpeedPlus.WHEEL_28), 0f)
        assertEquals(1f, SpeedPlus.factor(SpeedPlus.WHEEL_24, SpeedPlus.WHEEL_24), 0f)
        assertEquals(1f, SpeedPlus.factor(null, SpeedPlus.WHEEL_24), 0f)
    }

    @Test fun realSpeedAndCaps() {
        assertEquals(30, SpeedPlus.realKmh(25, SpeedPlus.FACTOR))
        assertEquals(38, SpeedPlus.realKmh(32, SpeedPlus.FACTOR))
        assertEquals(25, SpeedPlus.realKmh(25, 1f))
        assertNull(SpeedPlus.realKmh(null, SpeedPlus.FACTOR))
        assertEquals(29.6875f, SpeedPlus.realCapKmh(25), 1e-4f)
    }

    @Test fun stockWheelFromModel() {
        assertEquals(SpeedPlus.WHEEL_28, SpeedPlus.stockFromModel(bike("S3 2022")))
        assertEquals(SpeedPlus.WHEEL_24, SpeedPlus.stockFromModel(bike("X3")))
        assertNull(SpeedPlus.stockFromModel(bike("VM01-200-2G EU")))
        assertNull(SpeedPlus.stockFromModel(null))
    }

    // --- the factory wheel size ---------------------------------------------------------------------

    @Test fun modelNamesSettleTheWheelOnlyWhenTheyNameOneModel() {
        assertEquals(WHEEL_28, SpeedPlus.wheelOfModel("VanMoof S3"))
        assertEquals(WHEEL_28, SpeedPlus.wheelOfModel("electrified s3"))
        assertEquals(WHEEL_28, SpeedPlus.wheelOfModel("S3-2022"))
        assertEquals(WHEEL_24, SpeedPlus.wheelOfModel("VanMoof X3"))
        // What the account really reports is a SKU; the mainware calls both models "ES3".
        assertNull(SpeedPlus.wheelOfModel("VM01-203-EU"))
        assertNull(SpeedPlus.wheelOfModel("ES3.1 Main"))
        assertNull(SpeedPlus.wheelOfModel("S3/X3"))
        assertNull(SpeedPlus.wheelOfModel("S30"))
        assertNull(SpeedPlus.wheelOfModel(""))
        assertNull(SpeedPlus.wheelOfModel(null))
    }

    private fun stock(model: String? = null, device: String? = null, answer: Int? = null, first: Int? = null, current: Int? = null) =
        SpeedPlus.stock(model, device, answer, first, current)

    @Test fun theMostCertainSourceWins() {
        assertEquals(WheelStock(WHEEL_24, Source.MODEL), stock(model = "X3", device = "S3", answer = WHEEL_28, first = WHEEL_28, current = WHEEL_28))
        assertEquals(WheelStock(WHEEL_28, Source.BIKE), stock(model = "VM01-203-EU", device = "S3", answer = WHEEL_24, current = WHEEL_24))
        assertEquals(WheelStock(WHEEL_28, Source.OWNER), stock(model = "VM01-203-EU", answer = WHEEL_28, first = WHEEL_24, current = WHEEL_24))
        assertEquals(WheelStock(WHEEL_24, Source.OWNER), stock(answer = WHEEL_24, first = WHEEL_28, current = WHEEL_28))
    }

    @Test fun a28InchReadSettlesIt() {
        // YABA saw 28″ before Speed++ was switched on: the factor stays right across restarts (1.1 and 1.1.1 behaviour).
        assertEquals(WheelStock(WHEEL_28, Source.READ), stock(model = "VM01-203-EU", first = WHEEL_28, current = WHEEL_24))
        // Back on 28″ now, whatever YABA remembered first.
        assertEquals(WheelStock(WHEEL_28, Source.READ), stock(first = WHEEL_24, current = WHEEL_28))
        assertEquals(WheelStock(WHEEL_28, Source.READ), stock(current = WHEEL_28))
    }

    @Test fun a24InchReadAloneSettlesNothing() {
        // The owner's 1.1.2 case: a new install, the bike still on Speed++, the account naming a SKU. Before: "stock 24",
        // the sentence "only on 28″" and no switch, and the speed shown 16 % low.
        assertEquals(WheelStock.UNKNOWN, stock(model = "VM01-203-EU", first = WHEEL_24, current = WHEEL_24))
        assertEquals(WheelStock.UNKNOWN, stock(current = WHEEL_24))
        assertEquals(WheelStock.UNKNOWN, stock())
        assertEquals(1f, SpeedPlus.factor(WheelStock.UNKNOWN.wheel, WHEEL_24), 0f)
    }

    // --- what the block shows -----------------------------------------------------------------------

    @Test fun anS3OwnerAlwaysHasAControl() {
        val unknown = WheelStock.UNKNOWN
        val s3 = WheelStock(WHEEL_28, Source.MODEL)
        assertEquals(SpeedPlusView.OFFLINE, SpeedPlus.view(ready = false, wheel = null, stock = unknown))
        assertEquals(SpeedPlusView.OFFLINE, SpeedPlus.view(ready = false, wheel = WHEEL_24, stock = s3))
        assertEquals(SpeedPlusView.UNREAD, SpeedPlus.view(ready = true, wheel = null, stock = unknown))
        assertEquals(SpeedPlusView.UNREAD, SpeedPlus.view(ready = true, wheel = null, stock = s3))
        assertEquals(SpeedPlusView.ASK, SpeedPlus.view(ready = true, wheel = WHEEL_24, stock = unknown))
        assertEquals(SpeedPlusView.SWITCH, SpeedPlus.view(ready = true, wheel = WHEEL_24, stock = s3))
        assertEquals(SpeedPlusView.SWITCH, SpeedPlus.view(ready = true, wheel = WHEEL_28, stock = WheelStock(WHEEL_28, Source.READ)))
    }

    @Test fun onlyAFactory24InchBikeGetsTheSentence() {
        val x3 = WheelStock(WHEEL_24, Source.MODEL)
        assertEquals(SpeedPlusView.NOT_28, SpeedPlus.view(ready = true, wheel = WHEEL_24, stock = x3))
        assertEquals(SpeedPlusView.NOT_28, SpeedPlus.view(ready = false, wheel = null, stock = x3))
        assertEquals(SpeedPlusView.NOT_28, SpeedPlus.view(ready = true, wheel = WHEEL_24, stock = WheelStock(WHEEL_24, Source.OWNER)))
    }

    @Test fun theAnswerUnlocksTheSwitchAndTheFactor() {
        val before = stock(model = "VM01-203-EU", first = WHEEL_24, current = WHEEL_24)
        assertEquals(SpeedPlusView.ASK, SpeedPlus.view(true, WHEEL_24, before))
        val after = stock(model = "VM01-203-EU", answer = WHEEL_28, first = WHEEL_24, current = WHEEL_24)
        assertEquals(SpeedPlusView.SWITCH, SpeedPlus.view(true, WHEEL_24, after))
        assertEquals(SpeedPlus.FACTOR, SpeedPlus.factor(after.wheel, WHEEL_24), 0f)
    }

    @Test fun theStockBarStaysWhileTheAnswerIsTheOwners() {
        assertTrue(SpeedPlus.showsStockChoice(SpeedPlusView.ASK, WheelStock.UNKNOWN))
        assertTrue(SpeedPlus.showsStockChoice(SpeedPlusView.SWITCH, WheelStock(WHEEL_28, Source.OWNER)))
        assertTrue(SpeedPlus.showsStockChoice(SpeedPlusView.NOT_28, WheelStock(WHEEL_24, Source.OWNER)))
        assertFalse(SpeedPlus.showsStockChoice(SpeedPlusView.SWITCH, WheelStock(WHEEL_28, Source.READ)))
        assertFalse(SpeedPlus.showsStockChoice(SpeedPlusView.NOT_28, WheelStock(WHEEL_24, Source.MODEL)))
        assertFalse(SpeedPlus.showsStockChoice(SpeedPlusView.OFFLINE, WheelStock.UNKNOWN))
    }

    // --- review: the dead ends that were left -------------------------------------------------------

    @Test fun aFactory24InchBikeOn28ReadsHighAndIsScaledBack() {
        assertEquals(1f / SpeedPlus.FACTOR, SpeedPlus.factor(WHEEL_24, WHEEL_28), 0f)
        // the bike shows 30 when it rides 25
        assertEquals(25, SpeedPlus.realKmh(30, SpeedPlus.factor(WHEEL_24, WHEEL_28)))
        assertEquals(1f, SpeedPlus.factor(null, WHEEL_28), 0f)
    }

    @Test fun aFactory24InchBikeOn28GetsTheWayBack() {
        val x3 = WheelStock(WHEEL_24, Source.OWNER)
        assertEquals(SpeedPlusView.BACK_TO_24, SpeedPlus.view(ready = true, wheel = WHEEL_28, stock = x3))
        assertEquals(SpeedPlusView.BACK_TO_24, SpeedPlus.view(ready = true, wheel = WHEEL_28, stock = WheelStock(WHEEL_24, Source.MODEL)))
        // not connected: the sentence (nothing can be sent)
        assertEquals(SpeedPlusView.NOT_28, SpeedPlus.view(ready = false, wheel = WHEEL_28, stock = x3))
        // and the answer can still be put right there
        assertTrue(SpeedPlus.showsStockChoice(SpeedPlusView.BACK_TO_24, x3))
    }

    @Test fun a28InchReadStaysTheFirstReadOnceSeen() {
        assertEquals(WHEEL_24, SpeedPlus.firstReadAfter(null, WHEEL_24))
        assertEquals(WHEEL_28, SpeedPlus.firstReadAfter(null, WHEEL_28))
        assertEquals(WHEEL_28, SpeedPlus.firstReadAfter(WHEEL_28, WHEEL_24))
        // first read with Speed++ already on, later a 28″ read: it settles the stock for good, so switching Speed++ on
        // again keeps the switch (before: the question came back and the switch went away right after it was used)
        val first = SpeedPlus.firstReadAfter(WHEEL_24, WHEEL_28)
        assertEquals(WHEEL_28, first)
        val stock = stock(model = "VM01-203-EU", first = first, current = WHEEL_24)
        assertEquals(SpeedPlusView.SWITCH, SpeedPlus.view(true, WHEEL_24, stock))
        assertEquals(SpeedPlus.FACTOR, SpeedPlus.factor(stock.wheel, WHEEL_24), 0f)
    }
}
