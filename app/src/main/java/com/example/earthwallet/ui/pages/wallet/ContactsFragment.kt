package com.example.earthwallet.ui.pages.wallet

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.earthwallet.R
import com.example.earthwallet.wallet.services.ContactsManager

/**
 * ContactsFragment
 *
 * Manages a list of contacts with names and Secret Network addresses
 * for easy recipient selection when sending tokens.
 */
class ContactsFragment : Fragment() {

    companion object {
        private const val TAG = "ContactsFragment"
        private const val PREF_FILE = "contacts_prefs"
    }

    // UI Components
    private var backButton: ImageButton? = null
    private var addContactButton: Button? = null
    private var addContactForm: LinearLayout? = null
    private var contactNameEditText: EditText? = null
    private var contactAddressEditText: EditText? = null
    private var saveContactButton: Button? = null
    private var cancelContactButton: Button? = null
    private var contactsRecyclerView: RecyclerView? = null
    private var emptyStateLayout: LinearLayout? = null

    // Data
    private val contacts = mutableListOf<ContactsManager.Contact>()
    private var adapter: ContactsAdapter? = null
    private var isFormVisible = false

    // Interface for communication with parent
    interface ContactsListener {
        fun onContactSelected(name: String, address: String)
    }

    private var listener: ContactsListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = when {
            parentFragment is ContactsListener -> parentFragment as ContactsListener
            context is ContactsListener -> context
            else -> null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_contacts, container, false)

        // Initialize UI components
        backButton = view.findViewById(R.id.backButton)
        addContactButton = view.findViewById(R.id.addContactButton)
        addContactForm = view.findViewById(R.id.addContactForm)
        contactNameEditText = view.findViewById(R.id.contactNameEditText)
        contactAddressEditText = view.findViewById(R.id.contactAddressEditText)
        saveContactButton = view.findViewById(R.id.saveContactButton)
        cancelContactButton = view.findViewById(R.id.cancelContactButton)
        contactsRecyclerView = view.findViewById(R.id.contactsRecyclerView)
        emptyStateLayout = view.findViewById(R.id.emptyStateLayout)

        // ContactsManager handles all data operations
        setupRecyclerView()
        setupClickListeners()
        loadContacts()

        return view
    }

    private fun setupRecyclerView() {
        adapter = ContactsAdapter(
            contacts,
            object : ContactsAdapter.OnContactClickListener {
                override fun onContactClick(contact: ContactsManager.Contact) {
                    onContactClicked(contact)
                }
            },
            object : ContactsAdapter.OnContactLongClickListener {
                override fun onContactLongClick(contact: ContactsManager.Contact) {
                    onContactLongClicked(contact)
                }
            }
        )
        contactsRecyclerView?.layoutManager = LinearLayoutManager(context)
        contactsRecyclerView?.adapter = adapter
    }

    private fun setupClickListeners() {
        backButton?.setOnClickListener {
            // Navigate back to previous fragment
            parentFragmentManager.popBackStack()
        }

        addContactButton?.setOnClickListener { toggleAddContactForm() }
        saveContactButton?.setOnClickListener { saveContact() }
        cancelContactButton?.setOnClickListener { hideAddContactForm() }
    }

    private fun toggleAddContactForm() {
        if (isFormVisible) {
            hideAddContactForm()
        } else {
            showAddContactForm()
        }
    }

    private fun showAddContactForm() {
        addContactForm?.visibility = View.VISIBLE
        addContactButton?.text = "Cancel"
        isFormVisible = true
        contactNameEditText?.requestFocus()
    }

    private fun hideAddContactForm() {
        addContactForm?.visibility = View.GONE
        addContactButton?.text = "+ Add Contact"
        isFormVisible = false
        clearForm()
    }

    private fun clearForm() {
        contactNameEditText?.setText("")
        contactAddressEditText?.setText("")
    }

    private fun saveContact() {
        val name = contactNameEditText?.text?.toString()?.trim() ?: ""
        val address = contactAddressEditText?.text?.toString()?.trim() ?: ""

        when {
            TextUtils.isEmpty(name) -> {
                Toast.makeText(context, "Please enter contact name", Toast.LENGTH_SHORT).show()
                return
            }
            TextUtils.isEmpty(address) -> {
                Toast.makeText(context, "Please enter address", Toast.LENGTH_SHORT).show()
                return
            }
            !address.startsWith("secret1") -> {
                Toast.makeText(context, "Invalid Secret Network address", Toast.LENGTH_SHORT).show()
                return
            }
        }

        val success = ContactsManager.addContact(requireContext(), name, address)
        if (success) {
            loadContacts() // Reload contacts from manager
            hideAddContactForm()
            Toast.makeText(context, "Contact saved successfully", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Failed to save contact (duplicate or invalid)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadContacts() {
        contacts.clear()
        contacts.addAll(ContactsManager.getAllContacts(requireContext()))
        adapter?.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun updateEmptyState() {
        if (contacts.isEmpty()) {
            emptyStateLayout?.visibility = View.VISIBLE
            contactsRecyclerView?.visibility = View.GONE
        } else {
            emptyStateLayout?.visibility = View.GONE
            contactsRecyclerView?.visibility = View.VISIBLE
        }
    }

    private fun onContactClicked(contact: ContactsManager.Contact) {
        listener?.let {
            it.onContactSelected(contact.name, contact.address)
        } ?: run {
            // If no listener set, try to communicate with parent fragment via fragment result
            val result = Bundle().apply {
                putString("contact_name", contact.name)
                putString("contact_address", contact.address)
            }
            parentFragmentManager.setFragmentResult("contact_selected", result)

            // Navigate back
            parentFragmentManager.popBackStack()
        }
    }

    private fun onContactLongClicked(contact: ContactsManager.Contact) {
        AlertDialog.Builder(context)
            .setTitle("Delete Contact")
            .setMessage("Are you sure you want to delete ${contact.name}?")
            .setPositiveButton("Delete") { _, _ -> deleteContact(contact) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteContact(contact: ContactsManager.Contact) {
        val success = ContactsManager.removeContact(requireContext(), contact)
        if (success) {
            loadContacts() // Reload contacts from manager
            Toast.makeText(context, "Contact deleted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Failed to delete contact", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}