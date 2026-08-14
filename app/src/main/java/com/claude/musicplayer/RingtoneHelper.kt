package com.claude.musicplayer

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast

object RingtoneHelper {

    /**
     * Sets [song] as the device's default incoming-call ringtone. Requires
     * the "Modify system settings" special permission (like "All files
     * access", this can't be granted via a normal runtime dialog — it opens
     * system Settings for the user to approve).
     */
    fun setAsRingtone(activity: Activity, song: Song) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(activity)) {
            Toast.makeText(
                activity,
                "Grant \"Modify system settings\" for Music Player, then try again.",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
            return
        }

        try {
            val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id)

            // Mark it as a ringtone in MediaStore too, so it also shows up
            // in the system's own ringtone picker, not just via this app.
            val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_RINGTONE, true) }
            activity.contentResolver.update(contentUri, values, null, null)

            RingtoneManager.setActualDefaultRingtoneUri(activity, RingtoneManager.TYPE_RINGTONE, contentUri)
            Toast.makeText(activity, "Set \"${song.title}\" as your ringtone.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Couldn't set ringtone: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
