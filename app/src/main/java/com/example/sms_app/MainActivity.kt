package com.example.sms_app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.sms_app.theme.SMSAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class Group(val id: String, val title: String)

data class Contact(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val groupIds: List<String>,
    val isSelected: Boolean = false
)

enum class MessageType(val label: String) {
    SMS("SMS"),
    LMS("LMS (장문)"),
    MMS("MMS (이미지)")
}

fun detectMessageType(partCount: Int, hasAttachment: Boolean): MessageType = when {
    hasAttachment -> MessageType.MMS
    partCount > 1 -> MessageType.LMS
    else -> MessageType.SMS
}

class MainActivity : ComponentActivity() {
    private var permissionRevision by mutableIntStateOf(0)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionRevision++
        if (permissions[Manifest.permission.READ_CONTACTS] != true) {
            Toast.makeText(this, "연락처를 표시하려면 연락처 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
        if (permissions[Manifest.permission.SEND_SMS] != true) {
            Toast.makeText(this, "SMS와 장문을 직접 보내려면 SMS 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            SMSAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SmsAppScreen(this, permissionRevision)
                }
            }
        }

        requestPermissionLauncher.launch(
            arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS)
        )
    }

    override fun onResume() {
        super.onResume()
        permissionRevision++
    }

    fun getGroups(): List<Group> {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return emptyList()

        val groups = mutableListOf<Group>()
        contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
            "${ContactsContract.Groups.DELETED} = 0",
            null,
            "${ContactsContract.Groups.TITLE} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups._ID)
            val titleIndex = cursor.getColumnIndexOrThrow(ContactsContract.Groups.TITLE)
            while (cursor.moveToNext()) {
                val title = cursor.getString(titleIndex)
                if (!title.isNullOrBlank()) groups += Group(cursor.getString(idIndex), title)
            }
        }
        return groups
    }

    fun getContacts(): List<Contact> {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return emptyList()

        val groupMemberships = mutableMapOf<String, MutableList<String>>()
        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID
            ),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
            null
        )?.use { cursor ->
            val contactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.Data.CONTACT_ID)
            val groupIdIndex = cursor.getColumnIndexOrThrow(
                ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID
            )
            while (cursor.moveToNext()) {
                val contactId = cursor.getString(contactIdIndex)
                val groupId = cursor.getString(groupIdIndex)
                if (contactId != null && groupId != null) {
                    groupMemberships.getOrPut(contactId) { mutableListOf() } += groupId
                }
            }
        }

        val contacts = mutableListOf<Contact>()
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIndex)
                contacts += Contact(
                    id = id,
                    name = cursor.getString(nameIndex).orEmpty(),
                    phoneNumber = cursor.getString(numberIndex).orEmpty(),
                    groupIds = groupMemberships[id].orEmpty()
                )
            }
        }
        return contacts.distinctBy { normalizePhoneNumber(it.phoneNumber) }
    }

    fun messagePartCount(message: String): Int {
        if (message.isEmpty()) return 0
        return getSystemService(SmsManager::class.java).divideMessage(message).size
    }

    fun sendTextMessages(phoneNumbers: List<String>, message: String): Boolean {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            Toast.makeText(this, "SMS 전송 권한이 없습니다.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            Toast.makeText(this, "이 기기는 문자 전송을 지원하지 않습니다.", Toast.LENGTH_SHORT).show()
            return false
        }

        return try {
            val smsManager = getSystemService(SmsManager::class.java)
            val parts = smsManager.divideMessage(message)
            lifecycleScope.launch {
                var successCount = 0
                phoneNumbers.forEachIndexed { index, number ->
                    val sent = runCatching {
                        if (parts.size == 1) {
                            smsManager.sendTextMessage(number, null, message, null, null)
                        } else {
                            smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                        }
                    }.isSuccess
                    if (sent) successCount++
                    if (index < phoneNumbers.lastIndex) delay(1_000L)
                }
                val type = if (parts.size == 1) "SMS" else "장문 문자"
                Toast.makeText(
                    this@MainActivity,
                    "$type 개별 전송 요청 완료: $successCount/${phoneNumbers.size}명",
                    Toast.LENGTH_LONG
                ).show()
            }
            Toast.makeText(this, "${phoneNumbers.size}명에게 개별 순차 전송을 시작합니다.", Toast.LENGTH_SHORT).show()
            true
        } catch (exception: Exception) {
            Toast.makeText(this, "전송 실패: ${exception.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun openMmsComposer(phoneNumbers: List<String>, message: String, attachmentUri: Uri): Boolean {
        val mimeType = contentResolver.getType(attachmentUri) ?: "image/*"
        if (!isAutoSendAccessibilityEnabled()) {
            MmsAutoSendController.clear(this)
            Toast.makeText(
                this,
                "33명 개별 MMS 자동 전송을 시작하려면 접근성 서비스를 켜 주세요.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        if (!isGoogleMessagesInstalled()) {
            Toast.makeText(this, "Google 메시지 앱이 설치되어 있지 않습니다.", Toast.LENGTH_LONG).show()
            return false
        }

        val started = MmsAutoSendController.startQueue(
            context = this,
            recipients = phoneNumbers,
            message = message,
            attachmentUri = attachmentUri,
            mimeType = mimeType
        )
        if (started) {
            Toast.makeText(this, "${phoneNumbers.size}명에게 개별 MMS 순차 전송을 시작합니다.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Google 메시지를 열 수 없습니다.", Toast.LENGTH_LONG).show()
        }
        return started
    }

    private fun isGoogleMessagesInstalled(): Boolean {
        return runCatching {
            packageManager.getApplicationInfo("com.google.android.apps.messaging", 0)
        }.isSuccess
    }

    fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun isAutoSendAccessibilityEnabled(): Boolean {
        val manager = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                info.resolveInfo.serviceInfo.packageName == packageName &&
                    info.resolveInfo.serviceInfo.name == GoogleMessagesAutoSendService::class.java.name
            }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

private fun normalizePhoneNumber(number: String): String =
    number.filter { it.isDigit() || it == '+' }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsAppScreen(activity: MainActivity, permissionRevision: Int) {
    var message by remember { mutableStateOf("") }
    var attachmentUri by remember { mutableStateOf<Uri?>(null) }
    var contacts by remember { mutableStateOf(emptyList<Contact>()) }
    var groups by remember { mutableStateOf(emptyList<Group>()) }
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        attachmentUri = uri
    }

    LaunchedEffect(permissionRevision) {
        isLoading = true
        contacts = activity.getContacts()
        groups = activity.getGroups()
        isLoading = false
    }

    val filteredContacts = if (selectedGroupId == null) {
        contacts
    } else {
        contacts.filter { selectedGroupId in it.groupIds }
    }
    val allSelected = filteredContacts.isNotEmpty() && filteredContacts.all { it.isSelected }
    val partCount = remember(message) { activity.messagePartCount(message) }
    val messageType = detectMessageType(partCount, attachmentUri != null)
    val autoSendEnabled = remember(permissionRevision) {
        activity.isAutoSendAccessibilityEnabled()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("단체 SMS / LMS / MMS") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val selectedNumbers = contacts.filter { it.isSelected }.map { it.phoneNumber }
                    when {
                        selectedNumbers.isEmpty() -> Toast.makeText(
                            activity,
                            "연락처를 하나 이상 선택해 주세요.",
                            Toast.LENGTH_SHORT
                        ).show()
                        message.isBlank() && attachmentUri == null -> Toast.makeText(
                            activity,
                            "메시지나 이미지를 입력해 주세요.",
                            Toast.LENGTH_SHORT
                        ).show()
                        attachmentUri != null -> {
                            activity.openMmsComposer(selectedNumbers, message, attachmentUri!!)
                        }
                        activity.sendTextMessages(selectedNumbers, message) -> {
                            contacts = contacts.map { it.copy(isSelected = false) }
                        }
                    }
                },
                content = { Text("${messageType.label} 보내기 (${contacts.count { it.isSelected }}명)") }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("보낼 메시지") },
                supportingText = {
                    val detail = when (messageType) {
                        MessageType.SMS -> "SMS 1건"
                        MessageType.LMS -> "장문 문자: SMS ${partCount}개 구간으로 분할 전송"
                        MessageType.MMS -> if (autoSendEnabled) {
                            "수신자별로 Google 메시지를 열어 개별 순차 전송합니다"
                        } else {
                            "접근성 서비스를 켜야 자동 전송됩니다"
                        }
                    }
                    Text(detail)
                },
                maxLines = 6
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = { imagePicker.launch("image/*") }) {
                    Text(if (attachmentUri == null) "이미지 첨부" else "이미지 변경")
                }
                if (attachmentUri != null) {
                    OutlinedButton(onClick = { attachmentUri = null }) { Text("첨부 삭제") }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (autoSendEnabled) "Google 메시지 자동 전송: 켜짐"
                    else "Google 메시지 자동 전송: 꺼짐",
                    color = if (autoSendEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                OutlinedButton(onClick = activity::openAccessibilitySettings) {
                    Text("접근성 설정")
                }
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedGroupId == null,
                        onClick = {
                            selectedGroupId = null
                            contacts = contacts.map { it.copy(isSelected = false) }
                        },
                        label = { Text("전체") }
                    )
                }
                items(groups) { group ->
                    FilterChip(
                        selected = selectedGroupId == group.id,
                        onClick = {
                            selectedGroupId = group.id
                            contacts = contacts.map { it.copy(isSelected = false) }
                        },
                        label = { Text(group.title) }
                    )
                }
            }

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp)
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val newStatus = !allSelected
                            val visibleKeys = filteredContacts.map { it.id to it.phoneNumber }.toSet()
                            contacts = contacts.map { contact ->
                                if ((contact.id to contact.phoneNumber) in visibleKeys) {
                                    contact.copy(isSelected = newStatus)
                                } else contact
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = allSelected, onCheckedChange = null)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("전체 선택 (${filteredContacts.size}명)", style = MaterialTheme.typography.titleMedium)
                }

                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(filteredContacts, key = { "${it.id}:${it.phoneNumber}" }) { contact ->
                        ContactItem(contact) { isChecked ->
                            contacts = contacts.map {
                                if (it.id == contact.id && it.phoneNumber == contact.phoneNumber) {
                                    it.copy(isSelected = isChecked)
                                } else it
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ContactItem(contact: Contact, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!contact.isSelected) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = contact.isSelected, onCheckedChange = null)
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(contact.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                contact.phoneNumber,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
