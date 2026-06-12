package com.example.sms_app

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTypeTest {
    @Test
    fun onePartWithoutAttachmentIsSms() {
        assertEquals(MessageType.SMS, detectMessageType(partCount = 1, hasAttachment = false))
    }

    @Test
    fun multiplePartsWithoutAttachmentIsLms() {
        assertEquals(MessageType.LMS, detectMessageType(partCount = 2, hasAttachment = false))
    }

    @Test
    fun attachmentAlwaysMakesMessageMms() {
        assertEquals(
            MessageType.MMS,
            detectMessageType(partCount = 1, hasAttachment = true)
        )
    }
}
