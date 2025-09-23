package network.erth.wallet.ui.pages.wallet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import network.erth.wallet.R
import network.erth.wallet.wallet.services.ContactsManager

/**
 * ContactsAdapter
 *
 * RecyclerView adapter for displaying contacts list
 */
class ContactsAdapter(
    private val contacts: List<ContactsManager.Contact>,
    private val clickListener: OnContactClickListener?,
    private val longClickListener: OnContactLongClickListener?
) : RecyclerView.Adapter<ContactsAdapter.ContactViewHolder>() {

    interface OnContactClickListener {
        fun onContactClick(contact: ContactsManager.Contact)
    }

    interface OnContactLongClickListener {
        fun onContactLongClick(contact: ContactsManager.Contact)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]
        holder.bind(contact)
    }

    override fun getItemCount(): Int = contacts.size

    inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameTextView: TextView = itemView.findViewById(R.id.contactName)
        private val addressTextView: TextView = itemView.findViewById(R.id.contactAddress)

        fun bind(contact: ContactsManager.Contact) {
            nameTextView.text = contact.name
            addressTextView.text = formatAddress(contact.address)

            itemView.setOnClickListener {
                clickListener?.onContactClick(contact)
            }

            itemView.setOnLongClickListener {
                longClickListener?.onContactLongClick(contact)
                true
            }
        }

        private fun formatAddress(address: String): String {
            return if (address.length > 20) {
                "${address.substring(0, 14)}...${address.substring(address.length - 6)}"
            } else {
                address
            }
        }
    }
}