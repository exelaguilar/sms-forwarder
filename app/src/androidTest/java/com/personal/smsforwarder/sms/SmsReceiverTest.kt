package com.personal.smsforwarder.sms

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.personal.smsforwarder.core.ForwardDispatcher
import com.personal.smsforwarder.core.HistoryRecorder
import com.personal.smsforwarder.core.MessageProcessor
import com.personal.smsforwarder.model.ForwarderConfig
import com.personal.smsforwarder.model.HistoryEntry
import com.personal.smsforwarder.model.IncomingSms
import com.personal.smsforwarder.model.Rule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * Exercises the real parsing path the in-app simulator skips: PDU decoding and
 * multi-part concatenation. The PDUs below are built by hand as GSM SMS-DELIVER
 * messages with a UCS2 (UTF-16BE) payload, which avoids GSM 7-bit packing while still
 * going through Android's own [android.telephony.SmsMessage] decoder.
 */
@RunWith(AndroidJUnit4::class)
class SmsReceiverTest {

    private class RecordingDispatcher : ForwardDispatcher {
        val forwarded = mutableListOf<Triple<IncomingSms, String, String>>()
        override fun dispatch(
            historyEntryId: String,
            sms: IncomingSms,
            rule: Rule,
            config: ForwarderConfig,
        ) {
            forwarded += Triple(sms, rule.name, config.id)
        }
    }

    private class RecordingHistory : HistoryRecorder {
        val entries = mutableListOf<HistoryEntry>()
        override fun record(entry: HistoryEntry) {
            entries += entry
        }
    }

    private lateinit var context: Context
    private lateinit var dispatcher: RecordingDispatcher
    private lateinit var history: RecordingHistory
    private lateinit var originalFactory: (Context) -> MessageProcessor

    private val forwarder = ForwarderConfig.SmsRelay(id = "f-test", name = "Relay", destinationNumber = "+1555")
    private val matchAll = Rule(id = "r-all", name = "All", forwarderIds = listOf("f-test"))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dispatcher = RecordingDispatcher()
        history = RecordingHistory()
        val processor = MessageProcessor(
            rules = { listOf(matchAll) },
            forwarders = { listOf(forwarder) },
            history = history,
            dispatcher = dispatcher,
        )
        originalFactory = SmsReceiver.processorFactory
        SmsReceiver.processorFactory = { processor }
    }

    @After
    fun tearDown() {
        SmsReceiver.processorFactory = originalFactory
    }

    @Test
    fun singlePartMessageReachesTheProcessor() {
        val body = "Your code is 123456"
        SmsReceiver().onReceive(context, smsIntent(listOf(deliverPdu("15551234567", body))))

        val (sms, ruleName, forwarderId) = dispatcher.forwarded.single()
        assertEquals("+15551234567", sms.sender)
        assertEquals(body, sms.body)
        assertTrue("timestamp should be decoded from the PDU", sms.timestampMillis > 0)
        assertEquals("All", ruleName)
        assertEquals("f-test", forwarderId)
        assertEquals(1, history.entries.size)
    }

    @Test
    fun multiPartMessageIsConcatenatedIntoOneBody() {
        val part1 = "This is a long verification message that "
        val part2 = "continues in a second part: 987654"
        val pdus = listOf(
            deliverPdu("15551234567", part1, udh = concatUdh(ref = 0x42, total = 2, seq = 1)),
            deliverPdu("15551234567", part2, udh = concatUdh(ref = 0x42, total = 2, seq = 2)),
        )

        SmsReceiver().onReceive(context, smsIntent(pdus))

        val sms = dispatcher.forwarded.single().first
        assertEquals(part1 + part2, sms.body)
        assertEquals("+15551234567", sms.sender)
    }

    @Test
    fun intentsWithoutPdusAreIgnored() {
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
        SmsReceiver().onReceive(context, intent)
        assertTrue(dispatcher.forwarded.isEmpty())
        assertTrue(history.entries.isEmpty())
    }

    @Test
    fun otherActionsAreIgnored() {
        SmsReceiver().onReceive(
            context,
            smsIntent(listOf(deliverPdu("15551234567", "hi"))).setAction(Intent.ACTION_VIEW),
        )
        assertTrue(dispatcher.forwarded.isEmpty())
    }

    // ---- PDU construction ---------------------------------------------------

    /** Mirrors how the platform packs PDUs: a byte[][] under "pdus" plus the wire format. */
    private fun smsIntent(pdus: List<ByteArray>): Intent =
        Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("pdus", pdus.toTypedArray() as java.io.Serializable)
            putExtra("format", "3gpp")
        }

    private fun concatUdh(ref: Int, total: Int, seq: Int): ByteArray =
        byteArrayOf(0x05, 0x00, 0x03, ref.toByte(), total.toByte(), seq.toByte())

    /**
     * Builds a GSM SMS-DELIVER PDU:
     * [SMSC len=0][first octet][TP-OA][TP-PID][TP-DCS=UCS2][TP-SCTS][TP-UDL][TP-UD]
     */
    private fun deliverPdu(digits: String, text: String, udh: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00)                                  // no SMSC address
        out.write(if (udh != null) 0x44 else 0x04)       // SMS-DELIVER, UDHI set when there's a header

        out.write(digits.length)                         // address length in digits
        out.write(0x91)                                  // type-of-address: international
        val padded = if (digits.length % 2 == 0) digits else digits + "F"
        for (i in padded.indices step 2) {
            val low = Character.digit(padded[i], 16)
            val high = Character.digit(padded[i + 1], 16)
            out.write((high shl 4) or low)               // semi-octet swapped
        }

        out.write(0x00)                                  // TP-PID
        out.write(0x08)                                  // TP-DCS: UCS2

        // TP-SCTS: 2024-12-25 10:30:00, zone 0 — semi-octet swapped BCD.
        intArrayOf(24, 12, 25, 10, 30, 0, 0).forEach { out.write(swapBcd(it)) }

        val payload = text.toByteArray(Charsets.UTF_16BE)
        val userData = (udh ?: ByteArray(0)) + payload
        out.write(userData.size)                         // TP-UDL in octets (UCS2)
        out.write(userData)

        return out.toByteArray()
    }

    private fun swapBcd(value: Int): Int = ((value % 10) shl 4) or (value / 10)
}
