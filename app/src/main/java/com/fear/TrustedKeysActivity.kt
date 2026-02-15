package com.fear

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class TrustedKeysActivity : AppCompatActivity() {

    private lateinit var identityManager: IdentityManager
    private lateinit var keysRecyclerView: RecyclerView
    private lateinit var emptyTextView: TextView
    private lateinit var myFingerprintTextView: TextView
    private lateinit var adapter: TrustedKeysAdapter
    private val keys = mutableListOf<IdentityManager.KnownKey>()

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(ThemeManager.getTheme(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trusted_keys)

        identityManager = IdentityManager(this)

        myFingerprintTextView = findViewById(R.id.myFingerprintTextView)
        emptyTextView = findViewById(R.id.emptyTextView)
        keysRecyclerView = findViewById(R.id.keysRecyclerView)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        // Show my fingerprint
        if (identityManager.hasIdentity()) {
            val pk = identityManager.getPublicKey()!!
            val fp = identityManager.fingerprint(pk)
            myFingerprintTextView.text = "Fingerprint: $fp"
        } else {
            myFingerprintTextView.text = "No identity key generated"
        }

        adapter = TrustedKeysAdapter(keys, identityManager,
            onVerifyToggle = { key ->
                identityManager.setVerified(key.name, key.pk, !key.verified)
                refreshKeys()
            },
            onDelete = { key ->
                AlertDialog.Builder(this)
                    .setTitle("Delete Key")
                    .setMessage("Delete trusted key for \"${key.name}\"?\n\nFingerprint: ${identityManager.fingerprint(key.pk)}\n\nThey will be treated as a new contact next time.")
                    .setPositiveButton("Delete") { _, _ ->
                        identityManager.deleteKnownKey(key.name, key.pk)
                        refreshKeys()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        keysRecyclerView.layoutManager = LinearLayoutManager(this)
        keysRecyclerView.adapter = adapter

        refreshKeys()
    }

    private fun refreshKeys() {
        keys.clear()
        keys.addAll(identityManager.loadKnownKeys())
        adapter.notifyDataSetChanged()

        if (keys.isEmpty()) {
            emptyTextView.visibility = View.VISIBLE
            keysRecyclerView.visibility = View.GONE
        } else {
            emptyTextView.visibility = View.GONE
            keysRecyclerView.visibility = View.VISIBLE
        }
    }
}

class TrustedKeysAdapter(
    private val keys: List<IdentityManager.KnownKey>,
    private val identityManager: IdentityManager,
    private val onVerifyToggle: (IdentityManager.KnownKey) -> Unit,
    private val onDelete: (IdentityManager.KnownKey) -> Unit
) : RecyclerView.Adapter<TrustedKeysAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val nameTextView: TextView = itemView.findViewById(R.id.keyNameTextView)
        val statusTextView: TextView = itemView.findViewById(R.id.keyStatusTextView)
        val fingerprintTextView: TextView = itemView.findViewById(R.id.keyFingerprintTextView)
        val verifyButton: Button = itemView.findViewById(R.id.verifyButton)
        val deleteButton: Button = itemView.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_trusted_key, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val key = keys[position]
        val fp = identityManager.fingerprint(key.pk)

        holder.nameTextView.text = key.name
        holder.fingerprintTextView.text = fp

        if (key.verified) {
            holder.statusTextView.text = "[V] Verified"
            holder.statusTextView.setTextColor(0xFF2ecc71.toInt())
            holder.verifyButton.text = "Unverify"
            holder.verifyButton.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF95a5a6.toInt())
        } else {
            holder.statusTextView.text = "[T] Trusted"
            holder.statusTextView.setTextColor(0xFF3498db.toInt())
            holder.verifyButton.text = "Verify"
            holder.verifyButton.backgroundTintList =
                android.content.res.ColorStateList.valueOf(0xFF2ecc71.toInt())
        }

        holder.verifyButton.setOnClickListener { onVerifyToggle(key) }
        holder.deleteButton.setOnClickListener { onDelete(key) }
    }

    override fun getItemCount(): Int = keys.size
}
