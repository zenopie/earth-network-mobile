package com.example.earthwallet.wallet.services

import android.content.Context
import android.text.TextUtils
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * ContactsManager
 *
 * Manages contacts storage and retrieval using regular SharedPreferences.
 * Contacts are public information (names and addresses) so no encryption needed.
 */
object ContactsManager {

    private const val TAG = "ContactsManager"
    private const val PREF_FILE = "contacts_prefs"
    private const val CONTACTS_KEY = "contacts"

    /**
     * Contact data class
     */
    data class Contact(
        @JvmField val name: String,
        @JvmField val address: String
    )

    /**
     * Get all contacts
     */
    @JvmStatic
    fun getAllContacts(context: Context): List<Contact> {
        val contacts = mutableListOf<Contact>()
        try {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val contactsJson = prefs.getString(CONTACTS_KEY, "[]") ?: "[]"
            val contactsArray = JSONArray(contactsJson)

            for (i in 0 until contactsArray.length()) {
                val contactObj = contactsArray.getJSONObject(i)
                val name = contactObj.optString("name", "")
                val address = contactObj.optString("address", "")

                if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(address)) {
                    contacts.add(Contact(name, address))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load contacts", e)
        }
        return contacts
    }

    /**
     * Add a new contact
     */
    @JvmStatic
    fun addContact(context: Context, name: String, address: String): Boolean {
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(address)) {
            Log.w(TAG, "Cannot add contact with empty name or address")
            return false
        }

        if (!address.startsWith("secret1")) {
            Log.w(TAG, "Invalid Secret Network address: $address")
            return false
        }

        return try {
            val contacts = getAllContacts(context).toMutableList()

            // Check for duplicates
            for (contact in contacts) {
                if (contact.name.equals(name, ignoreCase = true)) {
                    Log.w(TAG, "Contact name already exists: $name")
                    return false
                }
                if (contact.address == address) {
                    Log.w(TAG, "Contact address already exists: $address")
                    return false
                }
            }

            // Add new contact
            contacts.add(Contact(name, address))
            saveContacts(context, contacts)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add contact", e)
            false
        }
    }

    /**
     * Remove a contact
     */
    @JvmStatic
    fun removeContact(context: Context, contactToRemove: Contact): Boolean {
        return try {
            val contacts = getAllContacts(context).toMutableList()
            val removed = contacts.removeIf { contact ->
                contact.name == contactToRemove.name && contact.address == contactToRemove.address
            }

            if (removed) {
                saveContacts(context, contacts)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove contact", e)
            false
        }
    }

    /**
     * Update an existing contact
     */
    @JvmStatic
    fun updateContact(context: Context, oldContact: Contact, newName: String, newAddress: String): Boolean {
        if (TextUtils.isEmpty(newName) || TextUtils.isEmpty(newAddress)) {
            return false
        }

        if (!newAddress.startsWith("secret1")) {
            return false
        }

        return try {
            val contacts = getAllContacts(context).toMutableList()

            // Check for duplicates (excluding the contact being updated)
            for (contact in contacts) {
                if (contact != oldContact) {
                    if (contact.name.equals(newName, ignoreCase = true)) {
                        Log.w(TAG, "Contact name already exists: $newName")
                        return false
                    }
                    if (contact.address == newAddress) {
                        Log.w(TAG, "Contact address already exists: $newAddress")
                        return false
                    }
                }
            }

            // Update the contact
            for (i in contacts.indices) {
                val contact = contacts[i]
                if (contact == oldContact) {
                    contacts[i] = Contact(newName, newAddress)
                    return saveContacts(context, contacts)
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update contact", e)
            false
        }
    }

    /**
     * Find a contact by name
     */
    @JvmStatic
    fun findContactByName(context: Context, name: String): Contact? {
        val contacts = getAllContacts(context)
        return contacts.find { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Find a contact by address
     */
    @JvmStatic
    fun findContactByAddress(context: Context, address: String): Contact? {
        val contacts = getAllContacts(context)
        return contacts.find { it.address == address }
    }

    /**
     * Check if contacts list is empty
     */
    @JvmStatic
    fun isEmpty(context: Context): Boolean {
        return getAllContacts(context).isEmpty()
    }

    /**
     * Get contact count
     */
    @JvmStatic
    fun getContactCount(context: Context): Int {
        return getAllContacts(context).size
    }

    /**
     * Clear all contacts
     */
    @JvmStatic
    fun clearAllContacts(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            prefs.edit().remove(CONTACTS_KEY).apply()
            Log.d(TAG, "All contacts cleared")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear contacts", e)
            false
        }
    }

    /**
     * Save contacts list to preferences
     */
    private fun saveContacts(context: Context, contacts: List<Contact>): Boolean {
        return try {
            val contactsArray = JSONArray()
            for (contact in contacts) {
                val contactObj = JSONObject()
                contactObj.put("name", contact.name)
                contactObj.put("address", contact.address)
                contactsArray.put(contactObj)
            }

            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            prefs.edit().putString(CONTACTS_KEY, contactsArray.toString()).apply()
            Log.d(TAG, "Saved ${contacts.size} contacts")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save contacts", e)
            false
        }
    }
}