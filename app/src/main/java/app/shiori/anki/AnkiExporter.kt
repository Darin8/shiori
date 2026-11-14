package app.shiori.anki

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.ichi2.anki.api.AddContentApi
import java.io.File

/**
 * Adds cards to AnkiDroid through its API. Every function that touches the API is blocking (it hits a
 * ContentProvider) and must be called off the main thread.
 */
class AnkiExporter(private val context: Context) {
    private val tag = "Shiori"

    private fun packageName(): String? = AddContentApi.getAnkiDroidPackageName(context)

    private fun permitted() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun target(): AnkiTarget {
        val installed = packageName() != null
        val permitted = permitted()
        if (!installed || !permitted) return resolveTarget(installed, permitted, emptyMap(), emptyMap()) { emptyList() }
        return try {
            val api = AddContentApi(context)
            resolveTarget(true, true, api.modelList ?: emptyMap(), api.deckList ?: emptyMap()) {
                api.getFieldList(it)?.toList() ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e(tag, "AnkiDroid target lookup failed", e)
            AnkiTarget.Unavailable(ANKI_ERROR_LABEL)
        }
    }

    /** Whether a note with this first-field value exists anywhere in the collection (this note type). */
    fun isDuplicate(t: AnkiTarget.Ok, key: String): Boolean = try {
        AddContentApi(context).findDuplicateNotes(t.modelId, key).isNotEmpty()
    } catch (e: Exception) {
        Log.e(tag, "AnkiDroid duplicate check failed", e)
        false
    }

    /** Returns the new note id, or null on failure. */
    fun add(t: AnkiTarget.Ok, input: CardInput, frame: Bitmap): Long? {
        val ankiPackage = packageName() ?: return null
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "shiori_${System.currentTimeMillis()}.webp")
        return try {
            file.outputStream().use { frame.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.grantUriPermission(ankiPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                val api = AddContentApi(context)
                val returned = api.addMediaFromUri(uri, "shiori_${System.currentTimeMillis()}", "image")
                val name = returned?.let { parseMediaFileName(it) } ?: return null
                val fields = buildFields(input.copy(pictureFileName = name), t.fieldNames)
                val values = t.fieldNames.map { fields[it] ?: "" }.toTypedArray()
                api.addNote(t.modelId, t.deckId, values, setOf("yomitan", "retromine"))
            } finally {
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(tag, "AnkiDroid add failed", e)
            null
        } finally {
            file.delete()
        }
    }

    /** Runtime permission request (dangerous permission); pass the result to requestPermissions. */
    fun requestPermissionIntent(): Array<String> = arrayOf(PERMISSION)

    /** Opens the store listing for AnkiDroid. */
    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.ichi2.anki"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    }
}
