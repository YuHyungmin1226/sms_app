package com.example.sms_app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.sms_app.theme.SMSAppTheme

data class Group(val id: String, val title: String)
data class Contact(val id: String, val name: String, val phoneNumber: String, val groupIds: List<String>, var isSelected: Boolean = false)

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.READ_CONTACTS] == true &&
            permissions[Manifest.permission.SEND_SMS] == true) {
            // Permissions granted
        } else {
            Toast.makeText(this, "연락처 및 SMS 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestPermissionLauncher.launch(arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        ))

        setContent {
            SMSAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SmsAppScreen(this)
                }
            }
        }
    }

    fun getGroups(): List<Group> {
        val groups = mutableListOf<Group>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return groups
        }

        val cursor = contentResolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
            "${ContactsContract.Groups.DELETED} = 0",
            null,
            ContactsContract.Groups.TITLE + " ASC"
        )

        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.Groups._ID)
            val titleIndex = it.getColumnIndex(ContactsContract.Groups.TITLE)

            while (it.moveToNext()) {
                val id = it.getString(idIndex)
                val title = it.getString(titleIndex)
                if (!title.isNullOrBlank()) {
                    groups.add(Group(id, title))
                }
            }
        }
        return groups
    }

    fun getContacts(): List<Contact> {
        val contacts = mutableListOf<Contact>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return contacts
        }

        // 1. Get Group Memberships
        val groupMemberships = mutableMapOf<String, MutableList<String>>() // contactId -> list of groupIds
        val groupCursor = contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
            null
        )
        groupCursor?.use {
            val contactIdIndex = it.getColumnIndex(ContactsContract.Data.CONTACT_ID)
            val groupIdIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID)
            while (it.moveToNext()) {
                val contactId = it.getString(contactIdIndex)
                val groupId = it.getString(groupIdIndex)
                if (contactId != null && groupId != null) {
                    groupMemberships.getOrPut(contactId) { mutableListOf() }.add(groupId)
                }
            }
        }

        // 2. Get Contacts
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )

        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val id = it.getString(idIndex)
                val name = it.getString(nameIndex)
                val number = it.getString(numberIndex)
                val gIds = groupMemberships[id] ?: emptyList()
                contacts.add(Contact(id, name ?: "", number ?: "", gIds))
            }
        }
        // Remove duplicates by normalized phone number
        return contacts.distinctBy { it.phoneNumber.replace("-", "").replace(" ", "") }
    }

    fun sendSms(phoneNumbers: List<String>, message: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "SMS 전송 권한이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val smsManager: SmsManager = this.getSystemService(SmsManager::class.java)
            for (number in phoneNumbers) {
                smsManager.sendTextMessage(number, null, message, null, null)
            }
            Toast.makeText(this, "메시지를 성공적으로 전송했습니다.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "전송 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsAppScreen(activity: MainActivity) {
    var message by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf(emptyList<Contact>()) }
    var groups by remember { mutableStateOf(emptyList<Group>()) }
    var selectedGroupId by remember { mutableStateOf<String?>(null) } // null means "All"
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        contacts = activity.getContacts()
        groups = activity.getGroups()
        isLoading = false
    }

    val filteredContacts = remember(contacts, selectedGroupId) {
        if (selectedGroupId == null) {
            contacts
        } else {
            contacts.filter { it.groupIds.contains(selectedGroupId) }
        }
    }

    val allSelected = filteredContacts.isNotEmpty() && filteredContacts.all { it.isSelected }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("미니멀 다중 SMS") }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val selectedNumbers = contacts.filter { it.isSelected }.map { it.phoneNumber }
                    if (selectedNumbers.isEmpty()) {
                        Toast.makeText(activity, "연락처를 하나 이상 선택해주세요.", Toast.LENGTH_SHORT).show()
                    } else if (message.isBlank()) {
                        Toast.makeText(activity, "보낼 메시지를 입력해주세요.", Toast.LENGTH_SHORT).show()
                    } else {
                        activity.sendSms(selectedNumbers, message)
                        contacts = contacts.map { it.copy(isSelected = false) }
                    }
                },
                content = { Text("전송 (${contacts.count { it.isSelected }}명)") }
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
                label = { Text("보낼 메시지 입력") },
                maxLines = 5
            )

            // Group Chips
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
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
            } else {
                // Select All Checkbox
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val newStatus = !allSelected
                            contacts = contacts.map { contact ->
                                if (filteredContacts.any { it.id == contact.id && it.phoneNumber == contact.phoneNumber }) {
                                    contact.copy(isSelected = newStatus)
                                } else {
                                    contact
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = allSelected,
                        onCheckedChange = null // handled by row click
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = "전체 선택 (${filteredContacts.size}명)", style = MaterialTheme.typography.titleMedium)
                }

                Divider()

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp) // space for FAB
                ) {
                    items(filteredContacts) { contact ->
                        ContactItem(
                            contact = contact,
                            onCheckedChange = { isChecked ->
                                contacts = contacts.map {
                                    if (it.id == contact.id && it.phoneNumber == contact.phoneNumber) {
                                        it.copy(isSelected = isChecked)
                                    } else {
                                        it
                                    }
                                }
                            }
                        )
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
        Checkbox(
            checked = contact.isSelected,
            onCheckedChange = null // handled by row click
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(text = contact.name, style = MaterialTheme.typography.bodyLarge)
            Text(text = contact.phoneNumber, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
