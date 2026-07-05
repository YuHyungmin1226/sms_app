package com.example.sms_app

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTypeTest {
    @Test
    fun zeroOrOnePartWithoutAttachmentIsSms() {
        assertClassification(
            ClassificationCase(
                name = "empty draft stays in SMS mode",
                partCount = 0,
                hasAttachment = false,
                expected = MessageType.SMS
            ),
            ClassificationCase(
                name = "single-part text is SMS",
                partCount = 1,
                hasAttachment = false,
                expected = MessageType.SMS
            )
        )
    }

    @Test
    fun multiplePartsWithoutAttachmentIsLms() {
        assertClassification(
            ClassificationCase(
                name = "two text parts become LMS",
                partCount = 2,
                hasAttachment = false,
                expected = MessageType.LMS
            ),
            ClassificationCase(
                name = "larger multipart text stays LMS",
                partCount = 5,
                hasAttachment = false,
                expected = MessageType.LMS
            )
        )
    }

    @Test
    fun attachmentAlwaysMakesMessageMms() {
        assertClassification(
            ClassificationCase(
                name = "image-only draft is MMS",
                partCount = 0,
                hasAttachment = true,
                expected = MessageType.MMS
            ),
            ClassificationCase(
                name = "attachment with short text is MMS",
                partCount = 1,
                hasAttachment = true,
                expected = MessageType.MMS
            ),
            ClassificationCase(
                name = "attachment overrides multipart text classification",
                partCount = 4,
                hasAttachment = true,
                expected = MessageType.MMS
            )
        )
    }

    private fun assertClassification(vararg cases: ClassificationCase) {
        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                detectMessageType(partCount = case.partCount, hasAttachment = case.hasAttachment)
            )
        }
    }

    private data class ClassificationCase(
        val name: String,
        val partCount: Int,
        val hasAttachment: Boolean,
        val expected: MessageType
    )
}
