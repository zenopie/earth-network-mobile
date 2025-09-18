package com.example.earthwallet.ui.pages.wallet;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.earthwallet.R;
import com.example.earthwallet.wallet.services.ContactsManager;

import java.util.List;

/**
 * ContactsAdapter
 *
 * RecyclerView adapter for displaying contacts list
 */
public class ContactsAdapter extends RecyclerView.Adapter<ContactsAdapter.ContactViewHolder> {

    private final List<ContactsManager.Contact> contacts;
    private final OnContactClickListener clickListener;
    private final OnContactLongClickListener longClickListener;

    public interface OnContactClickListener {
        void onContactClick(ContactsManager.Contact contact);
    }

    public interface OnContactLongClickListener {
        void onContactLongClick(ContactsManager.Contact contact);
    }

    public ContactsAdapter(List<ContactsManager.Contact> contacts,
                          OnContactClickListener clickListener,
                          OnContactLongClickListener longClickListener) {
        this.contacts = contacts;
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    @NonNull
    @Override
    public ContactViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_contact, parent, false);
        return new ContactViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ContactViewHolder holder, int position) {
        ContactsManager.Contact contact = contacts.get(position);
        holder.bind(contact);
    }

    @Override
    public int getItemCount() {
        return contacts.size();
    }

    class ContactViewHolder extends RecyclerView.ViewHolder {
        private final TextView nameTextView;
        private final TextView addressTextView;

        ContactViewHolder(@NonNull View itemView) {
            super(itemView);
            nameTextView = itemView.findViewById(R.id.contactName);
            addressTextView = itemView.findViewById(R.id.contactAddress);
        }

        void bind(ContactsManager.Contact contact) {
            nameTextView.setText(contact.name);
            addressTextView.setText(formatAddress(contact.address));

            itemView.setOnClickListener(v -> {
                if (clickListener != null) {
                    clickListener.onContactClick(contact);
                }
            });

            itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) {
                    longClickListener.onContactLongClick(contact);
                }
                return true;
            });
        }

        private String formatAddress(String address) {
            if (address.length() > 20) {
                return address.substring(0, 14) + "..." + address.substring(address.length() - 6);
            }
            return address;
        }
    }
}