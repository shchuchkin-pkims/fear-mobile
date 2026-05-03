package com.fear.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One contact in the user's local address book.
 *
 * `identityPkB64` is the 32-byte Ed25519 public key (URL-safe base64,
 * no padding) — the contact's permanent cryptographic identity. It's
 * the natural primary key: a contact is "the person who owns this pk",
 * any handle they register on any server is metadata pinned to it.
 *
 * `displayName` is what the local user wants to call this contact —
 * defaults to whatever the contact set as their own display name when
 * we first met them, but is editable.
 *
 * `handle`/`server` are the most-recently-known `nickname@server`
 * registration. Optional — a contact may exist without ever having a
 * handle (added by QR or by sharing identity_pk directly).
 *
 * `verified` is the TOFU-promotion flag — set after the user explicitly
 * confirms a fingerprint match.
 */
@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val identityPkB64: String,
    val displayName: String,
    val handle: String? = null,        // "nickname"
    val server: String? = null,        // "fear-project.ru"
    val addedAt: Long,                 // unix millis
    val verified: Boolean = false,
)
