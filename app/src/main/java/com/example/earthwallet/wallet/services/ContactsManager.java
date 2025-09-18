package com.example.earthwallet.wallet.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ContactsManager
 *
 * Manages contacts storage and retrieval using regular SharedPreferences.
 * Contacts are public information (names and addresses) so no encryption needed.
 */
public final class ContactsManager {

    private static final String TAG = "ContactsManager";
    private static final String PREF_FILE = "contacts_prefs";
    private static final String CONTACTS_KEY = "contacts";

    private ContactsManager() {}

    /**
     * Contact data class
     */
    public static class Contact {
        public final String name;
        public final String address;

        public Contact(String name, String address) {
            this.name = name;
            this.address = address;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Contact contact = (Contact) obj;
            return name.equals(contact.name) && address.equals(contact.address);
        }

        @Override
        public int hashCode() {
            return name.hashCode() + address.hashCode();
        }
    }

    /**
     * Get all contacts
     */
    public static List<Contact> getAllContacts(Context context) {
        List<Contact> contacts = new ArrayList<>();
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            String contactsJson = prefs.getString(CONTACTS_KEY, "[]");
            JSONArray contactsArray = new JSONArray(contactsJson);

            for (int i = 0; i < contactsArray.length(); i++) {
                JSONObject contactObj = contactsArray.getJSONObject(i);
                String name = contactObj.optString("name", "");
                String address = contactObj.optString("address", "");

                if (!TextUtils.isEmpty(name) && !TextUtils.isEmpty(address)) {
                    contacts.add(new Contact(name, address));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load contacts", e);
        }
        return contacts;
    }

    /**
     * Add a new contact
     */
    public static boolean addContact(Context context, String name, String address) {
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(address)) {
            Log.w(TAG, "Cannot add contact with empty name or address");
            return false;
        }

        if (!address.startsWith("secret1")) {
            Log.w(TAG, "Invalid Secret Network address: " + address);
            return false;
        }

        try {
            List<Contact> contacts = getAllContacts(context);

            // Check for duplicates
            for (Contact contact : contacts) {
                if (contact.name.equalsIgnoreCase(name)) {
                    Log.w(TAG, "Contact name already exists: " + name);
                    return false;
                }
                if (contact.address.equals(address)) {
                    Log.w(TAG, "Contact address already exists: " + address);
                    return false;
                }
            }

            // Add new contact
            contacts.add(new Contact(name, address));
            return saveContacts(context, contacts);

        } catch (Exception e) {
            Log.e(TAG, "Failed to add contact", e);
            return false;
        }
    }

    /**
     * Remove a contact
     */
    public static boolean removeContact(Context context, Contact contactToRemove) {
        try {
            List<Contact> contacts = getAllContacts(context);
            boolean removed = contacts.removeIf(contact ->
                contact.name.equals(contactToRemove.name) &&
                contact.address.equals(contactToRemove.address));

            if (removed) {
                return saveContacts(context, contacts);
            }
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to remove contact", e);
            return false;
        }
    }

    /**
     * Update an existing contact
     */
    public static boolean updateContact(Context context, Contact oldContact, String newName, String newAddress) {
        if (TextUtils.isEmpty(newName) || TextUtils.isEmpty(newAddress)) {
            return false;
        }

        if (!newAddress.startsWith("secret1")) {
            return false;
        }

        try {
            List<Contact> contacts = getAllContacts(context);

            // Check for duplicates (excluding the contact being updated)
            for (Contact contact : contacts) {
                if (!contact.equals(oldContact)) {
                    if (contact.name.equalsIgnoreCase(newName)) {
                        Log.w(TAG, "Contact name already exists: " + newName);
                        return false;
                    }
                    if (contact.address.equals(newAddress)) {
                        Log.w(TAG, "Contact address already exists: " + newAddress);
                        return false;
                    }
                }
            }

            // Update the contact
            for (int i = 0; i < contacts.size(); i++) {
                Contact contact = contacts.get(i);
                if (contact.equals(oldContact)) {
                    contacts.set(i, new Contact(newName, newAddress));
                    return saveContacts(context, contacts);
                }
            }

            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to update contact", e);
            return false;
        }
    }

    /**
     * Find a contact by name
     */
    public static Contact findContactByName(Context context, String name) {
        List<Contact> contacts = getAllContacts(context);
        for (Contact contact : contacts) {
            if (contact.name.equalsIgnoreCase(name)) {
                return contact;
            }
        }
        return null;
    }

    /**
     * Find a contact by address
     */
    public static Contact findContactByAddress(Context context, String address) {
        List<Contact> contacts = getAllContacts(context);
        for (Contact contact : contacts) {
            if (contact.address.equals(address)) {
                return contact;
            }
        }
        return null;
    }

    /**
     * Check if contacts list is empty
     */
    public static boolean isEmpty(Context context) {
        return getAllContacts(context).isEmpty();
    }

    /**
     * Get contact count
     */
    public static int getContactCount(Context context) {
        return getAllContacts(context).size();
    }

    /**
     * Clear all contacts
     */
    public static boolean clearAllContacts(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            prefs.edit().remove(CONTACTS_KEY).apply();
            Log.d(TAG, "All contacts cleared");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear contacts", e);
            return false;
        }
    }

    /**
     * Save contacts list to preferences
     */
    private static boolean saveContacts(Context context, List<Contact> contacts) {
        try {
            JSONArray contactsArray = new JSONArray();
            for (Contact contact : contacts) {
                JSONObject contactObj = new JSONObject();
                contactObj.put("name", contact.name);
                contactObj.put("address", contact.address);
                contactsArray.put(contactObj);
            }

            SharedPreferences prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            prefs.edit().putString(CONTACTS_KEY, contactsArray.toString()).apply();
            Log.d(TAG, "Saved " + contacts.size() + " contacts");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to save contacts", e);
            return false;
        }
    }
}