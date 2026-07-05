package com.example.sms_app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.view.accessibility.AccessibilityManager
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
import com.example.sms_app.theme.SMSAppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

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
    LMS("LMS (Long)"),
    MMS("MMS (Image)")
}

fun detectMessageType(partCount: Int, hasAttachment: Boolean): MessageType = when {
    hasAttachment -> MessageType.MMS
    partCount > 1 -> MessageType.LMS
    else -> MessageType.SMS
}

private object SmsBatchSender {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun send(
        appContext: Context,
        smsManager: SmsManager,
        recipients: List<String>,
        message: String,
        parts: ArrayList<String>
    ) {
        scope.launch {
            var sentCount = 0
            recipients.forEachIndexed { index, number ->
                val sent = runCatching {
                    if (parts.size == 1) {
                        smsManager.sendTextMessage(number, null, message, null, null)
                    } else {
                        smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                    }
                }.isSuccess
                if (sent) {
                    sentCount++
                }
                if (index < recipients.lastIndex) {
                    delay(1_000L)
                }
            }

            val messageKind = if (parts.size == 1) "SMS" else "LMS"
            mainHandler.post {
                Toast.makeText(
                    appContext,
                    "$messageKind send requests finished: $sentCount/${recipients.size}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    private var permissionRevision by mutableIntStateOf(0)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionRevision++
        if (permissions[Manifest.permission.READ_CONTACTS] != true) {
            Toast.makeText(
                this,
                "Contacts permission is required to list recipients.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (permissions[Manifest.permission.SEND_SMS] != true) {
            Toast.makeText(
                this,
                "SMS permission is required to send SMS or LMS directly.",
                Toast.LENGTH_LONG
            ).show()
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
                if (!title.isNullOrBlank()) {
                    groups += Group(cursor.getString(idIndex), title)
                }
            }
        }
        return groups
    }

    fun getContacts(): List<Contact> {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return emptyList()

        val groupMemberships = mutableMapOf<String, MutableSet<String>>()
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
                    groupMemberships.getOrPut(contactId) { linkedSetOf() }.add(groupId)
                }
            }
        }

        val contactsByNumber = linkedMapOf<String, Contact>()
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
                val phoneNumber = cursor.getString(numberIndex).orEmpty()
                val normalizedNumber = normalizePhoneNumber(phoneNumber)
                if (normalizedNumber.isBlank()) continue

                val nextContact = Contact(
                    id = id,
                    name = cursor.getString(nameIndex).orEmpty(),
                    phoneNumber = phoneNumber,
                    groupIds = groupMemberships[id].orEmpty().toList()
                )
                contactsByNumber[normalizedNumber] = contactsByNumber[normalizedNumber]
                    ?.mergeWith(nextContact)
                    ?: nextContact
            }
        }

        return contactsByNumber.values.toList()
    }

    fun messagePartCount(message: String): Int {
        if (message.isEmpty()) return 0
        val smsManager = getSystemService(SmsManager::class.java) ?: return 1
        return runCatching { smsManager.divideMessage(message).size }.getOrDefault(1)
    }

    fun sendTextMessages(phoneNumbers: List<String>, message: String): Boolean {
        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            Toast.makeText(this, "SMS permission is not granted.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
            Toast.makeText(this, "This device cannot send SMS messages.", Toast.LENGTH_SHORT).show()
            return false
        }

        val recipients = phoneNumbers
            .map(String::trim)
            .filter { normalizePhoneNumber(it).isNotBlank() }
        if (recipients.isEmpty()) {
            Toast.makeText(this, "No valid recipient numbers were selected.", Toast.LENGTH_SHORT).show()
            return false
        }

        val smsManager = getSystemService(SmsManager::class.java)
        if (smsManager == null) {
            Toast.makeText(this, "SmsManager is unavailable on this device.", Toast.LENGTH_SHORT).show()
            return false
        }

        return try {
            val parts = ArrayList(smsManager.divideMessage(message))
            SmsBatchSender.send(applicationContext, smsManager, recipients, message, parts)

            Toast.makeText(
                this,
                "Started sequential sending for ${recipients.size} recipients.",
                Toast.LENGTH_SHORT
            ).show()
            true
        } catch (exception: Exception) {
            Toast.makeText(this, "Send failed: ${exception.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    fun openMmsComposer(phoneNumbers: List<String>, message: String, attachmentUri: Uri): Boolean {
        val recipients = phoneNumbers
            .map(String::trim)
            .filter { normalizePhoneNumber(it).isNotBlank() }
        if (recipients.isEmpty()) {
            Toast.makeText(this, "No valid recipient numbers were selected.", Toast.LENGTH_SHORT).show()
            return false
        }

        val mimeType = contentResolver.getType(attachmentUri) ?: "image/*"
        if (!isAutoSendAccessibilityEnabled()) {
            MmsAutoSendController.clear(this)
            Toast.makeText(
                this,
                "Turn on the accessibility service before starting MMS auto-send.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        if (!isGoogleMessagesInstalled()) {
            Toast.makeText(this, "Google Messages is not installed.", Toast.LENGTH_LONG).show()
            return false
        }
        if (Telephony.Sms.getDefaultSmsPackage(this) != "com.google.android.apps.messaging") {
            Toast.makeText(
                this,
                "Set Google Messages as the default SMS app before using MMS auto-send.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        val started = MmsAutoSendController.startQueue(
            context = this,
            recipients = recipients,
            message = message,
            attachmentUri = attachmentUri,
            mimeType = mimeType
        )
        if (started) {
            Toast.makeText(
                this,
                "Queued ${recipients.size} MMS recipients in Google Messages.",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "Google Messages could not be opened.", Toast.LENGTH_LONG).show()
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
        val manager = getSystemService(AccessibilityManager::class.java) ?: return false
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

internal fun normalizePhoneNumber(number: String): String =
    number.filter { it.isDigit() || it == '+' }

private fun Contact.mergeWith(other: Contact): Contact {
    val mergedGroupIds = linkedSetOf<String>().apply {
        addAll(groupIds)
        addAll(other.groupIds)
    }
    return copy(
        name = if (name.isBlank()) other.name else name,
        groupIds = mergedGroupIds.toList()
    )
}

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
        val (loadedContacts, loadedGroups) = withContext(Dispatchers.IO) {
            activity.getContacts() to activity.getGroups()
        }
        contacts = loadedContacts
        groups = loadedGroups
        isLoading = false
    }

    val filteredContacts = if (selectedGroupId == null) {
        contacts
    } else {
        contacts.filter { selectedGroupId in it.groupIds }
    }
    val selectedCount = contacts.count { it.isSelected }
    val allSelected = filteredContacts.isNotEmpty() && filteredContacts.all { it.isSelected }
    val partCount = remember(message) { activity.messagePartCount(message) }
    val messageType = detectMessageType(partCount, attachmentUri != null)
    val autoSendEnabled = remember(permissionRevision) {
        activity.isAutoSendAccessibilityEnabled()
    }
    val supportCopy = when {
        message.isBlank() && attachmentUri == null -> "Enter a message or attach an image."
        messageType == MessageType.SMS -> "This will be sent as one SMS segment."
        messageType == MessageType.LMS -> "This will be split into $partCount SMS segments."
        autoSendEnabled -> "Google Messages will open and attempt a sequential MMS send."
        else -> "Enable the accessibility service before using MMS auto-send."
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Group SMS / LMS / MMS") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val selectedNumbers = contacts
                        .filter { it.isSelected }
                        .map { it.phoneNumber }

                    when {
                        selectedNumbers.isEmpty() -> Toast.makeText(
                            activity,
                            "Select at least one contact.",
                            Toast.LENGTH_SHORT
                        ).show()

                        message.isBlank() && attachmentUri == null -> Toast.makeText(
                            activity,
                            "Enter a message or attach an image first.",
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
                content = { Text("${messageType.label} Send ($selectedCount)") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("Message") },
                supportingText = { Text(supportCopy) },
                maxLines = 6
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = { imagePicker.launch("image/*") }) {
                    Text(if (attachmentUri == null) "Attach image" else "Change image")
                }
                if (attachmentUri != null) {
                    OutlinedButton(onClick = { attachmentUri = null }) {
                        Text("Remove attachment")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (autoSendEnabled) {
                        "Google Messages auto-send is on"
                    } else {
                        "Google Messages auto-send is off"
                    },
                    color = if (autoSendEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
                OutlinedButton(onClick = activity::openAccessibilitySettings) {
                    Text("Accessibility")
                }
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedGroupId == null,
                        onClick = {
                            selectedGroupId = null
                            contacts = contacts.map { it.copy(isSelected = false) }
                        },
                        label = { Text("All") }
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
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(16.dp)
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
                                } else {
                                    contact
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = allSelected, onCheckedChange = null)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Select all (${filteredContacts.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
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
                                } else {
                                    it
                                }
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
                text = contact.phoneNumber,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
