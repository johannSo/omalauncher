package dev.omalauncher.pp.search

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.util.Log
import dev.omalauncher.pp.widgets.lang.matchScore

/** A contact the bar found: [phone] is its main number, if it has one. */
class ContactHit(
    val id: Long,
    val lookup: String,
    val name: String,
    val phone: String?,
    val score: Int,
)

/**
 * Contacts by name, number or address, through Android's own contact search. Opt-in: only with style.toml's
 * search.contacts on and the contacts permission granted, which settings › search asks for as it's turned on.
 * Calling dials the number in the phone app rather than calling it, so the launcher needs no phone permission.
 */
class ContactSearch(
    private val context: Context,
) {
    fun granted() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Up to [MAX] contacts [query] finds, starred ones first. Blocks: call off the main thread. */
    fun search(query: String): List<ContactHit> {
        val q = query.trim()
        if (q.length < 2 || !granted()) return emptyList()
        val uri = Uri.withAppendedPath(Contacts.CONTENT_FILTER_URI, Uri.encode(q))
        val projection = arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.HAS_PHONE_NUMBER)
        val found = ArrayList<ContactHit>()
        runCatching {
            context.contentResolver.query(uri, projection, null, null, "${Contacts.STARRED} DESC")?.use { c ->
                while (c.moveToNext() && found.size < MAX) {
                    val id = c.getLong(0)
                    val name = c.getString(2) ?: continue
                    val phone = if (c.getInt(3) > 0) phoneOf(id) else null
                    // Found by a number or an address: listed, below those whose name it starts.
                    val score = matchScore(name, q).takeIf { it > 0 } ?: BY_DETAIL
                    found += ContactHit(id, c.getString(1).orEmpty(), name, phone, score)
                }
            }
        }.onFailure { Log.w(TAG, "contact search failed", it) }
        return found
    }

    /** The contact's main number: the one set as default, else its first. */
    private fun phoneOf(id: Long): String? =
        context.contentResolver
            .query(
                Phone.CONTENT_URI,
                arrayOf(Phone.NUMBER),
                "${Phone.CONTACT_ID} = ?",
                arrayOf(id.toString()),
                "${Phone.IS_SUPER_PRIMARY} DESC, ${Phone.IS_PRIMARY} DESC",
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** The phone app with [number] typed in, to call. */
    fun dial(number: String) = start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))

    /** The messaging app, writing to [number]. */
    fun message(number: String) = start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))

    /** The contacts app on [hit]. */
    fun open(hit: ContactHit) = start(Intent(Intent.ACTION_VIEW, Contacts.getLookupUri(hit.id, hit.lookup)))

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "nothing handles $intent", e)
        }
    }

    companion object {
        const val PERMISSION = Manifest.permission.READ_CONTACTS
        private const val TAG = "search"
        private const val MAX = 8

        /** matchScore's substring tier: below a name the query starts. */
        private const val BY_DETAIL = 200
    }
}
