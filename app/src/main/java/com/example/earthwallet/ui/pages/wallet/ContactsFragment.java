package com.example.earthwallet.ui.pages.wallet;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.earthwallet.R;
import com.example.earthwallet.wallet.services.ContactsManager;

import java.util.ArrayList;
import java.util.List;

/**
 * ContactsFragment
 *
 * Manages a list of contacts with names and Secret Network addresses
 * for easy recipient selection when sending tokens.
 */
public class ContactsFragment extends Fragment {

    private static final String TAG = "ContactsFragment";
    private static final String PREF_FILE = "contacts_prefs";

    // UI Components
    private ImageButton backButton;
    private Button addContactButton;
    private LinearLayout addContactForm;
    private EditText contactNameEditText;
    private EditText contactAddressEditText;
    private Button saveContactButton;
    private Button cancelContactButton;
    private RecyclerView contactsRecyclerView;
    private LinearLayout emptyStateLayout;

    // Data
    private List<ContactsManager.Contact> contacts;
    private ContactsAdapter adapter;
    private boolean isFormVisible = false;

    // Interface for communication with parent
    public interface ContactsListener {
        void onContactSelected(String name, String address);
    }

    private ContactsListener listener;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (getParentFragment() instanceof ContactsListener) {
            listener = (ContactsListener) getParentFragment();
        } else if (context instanceof ContactsListener) {
            listener = (ContactsListener) context;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_contacts, container, false);

        // Initialize UI components
        backButton = view.findViewById(R.id.backButton);
        addContactButton = view.findViewById(R.id.addContactButton);
        addContactForm = view.findViewById(R.id.addContactForm);
        contactNameEditText = view.findViewById(R.id.contactNameEditText);
        contactAddressEditText = view.findViewById(R.id.contactAddressEditText);
        saveContactButton = view.findViewById(R.id.saveContactButton);
        cancelContactButton = view.findViewById(R.id.cancelContactButton);
        contactsRecyclerView = view.findViewById(R.id.contactsRecyclerView);
        emptyStateLayout = view.findViewById(R.id.emptyStateLayout);

        // ContactsManager handles all data operations

        setupRecyclerView();
        setupClickListeners();
        loadContacts();

        return view;
    }

    private void setupRecyclerView() {
        contacts = new ArrayList<>();
        adapter = new ContactsAdapter(contacts, this::onContactClicked, this::onContactLongClicked);
        contactsRecyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        contactsRecyclerView.setAdapter(adapter);
    }

    private void setupClickListeners() {
        backButton.setOnClickListener(v -> {
            // Navigate back to previous fragment
            getParentFragmentManager().popBackStack();
        });
        addContactButton.setOnClickListener(v -> toggleAddContactForm());
        saveContactButton.setOnClickListener(v -> saveContact());
        cancelContactButton.setOnClickListener(v -> hideAddContactForm());
    }

    private void toggleAddContactForm() {
        if (isFormVisible) {
            hideAddContactForm();
        } else {
            showAddContactForm();
        }
    }

    private void showAddContactForm() {
        addContactForm.setVisibility(View.VISIBLE);
        addContactButton.setText("Cancel");
        isFormVisible = true;
        contactNameEditText.requestFocus();
    }

    private void hideAddContactForm() {
        addContactForm.setVisibility(View.GONE);
        addContactButton.setText("+ Add Contact");
        isFormVisible = false;
        clearForm();
    }

    private void clearForm() {
        contactNameEditText.setText("");
        contactAddressEditText.setText("");
    }

    private void saveContact() {
        String name = contactNameEditText.getText().toString().trim();
        String address = contactAddressEditText.getText().toString().trim();

        if (TextUtils.isEmpty(name)) {
            Toast.makeText(getContext(), "Please enter contact name", Toast.LENGTH_SHORT).show();
            return;
        }

        if (TextUtils.isEmpty(address)) {
            Toast.makeText(getContext(), "Please enter address", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!address.startsWith("secret1")) {
            Toast.makeText(getContext(), "Invalid Secret Network address", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean success = ContactsManager.addContact(requireContext(), name, address);
        if (success) {
            loadContacts(); // Reload contacts from manager
            hideAddContactForm();
            Toast.makeText(getContext(), "Contact saved successfully", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "Failed to save contact (duplicate or invalid)", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadContacts() {
        contacts.clear();
        contacts.addAll(ContactsManager.getAllContacts(requireContext()));
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }


    private void updateEmptyState() {
        if (contacts.isEmpty()) {
            emptyStateLayout.setVisibility(View.VISIBLE);
            contactsRecyclerView.setVisibility(View.GONE);
        } else {
            emptyStateLayout.setVisibility(View.GONE);
            contactsRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void onContactClicked(ContactsManager.Contact contact) {
        if (listener != null) {
            listener.onContactSelected(contact.name, contact.address);
        } else {
            // If no listener set, try to communicate with parent fragment via fragment result
            Bundle result = new Bundle();
            result.putString("contact_name", contact.name);
            result.putString("contact_address", contact.address);
            getParentFragmentManager().setFragmentResult("contact_selected", result);

            // Navigate back
            getParentFragmentManager().popBackStack();
        }
    }

    private void onContactLongClicked(ContactsManager.Contact contact) {
        new AlertDialog.Builder(getContext())
            .setTitle("Delete Contact")
            .setMessage("Are you sure you want to delete " + contact.name + "?")
            .setPositiveButton("Delete", (dialog, which) -> deleteContact(contact))
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deleteContact(ContactsManager.Contact contact) {
        boolean success = ContactsManager.removeContact(requireContext(), contact);
        if (success) {
            loadContacts(); // Reload contacts from manager
            Toast.makeText(getContext(), "Contact deleted", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(getContext(), "Failed to delete contact", Toast.LENGTH_SHORT).show();
        }
    }


    @Override
    public void onDetach() {
        super.onDetach();
        listener = null;
    }

}