/*
 *  Copyright (c) 2023 David Allison <davidallisongithub@gmail.com>
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.services

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ichi2.anki.*
import com.ichi2.anki.servicelayer.ScopedStorageService
import com.ichi2.anki.servicelayer.ScopedStorageService.PREF_MIGRATION_DESTINATION
import com.ichi2.anki.servicelayer.ScopedStorageService.PREF_MIGRATION_SOURCE
import com.ichi2.anki.servicelayer.scopedstorage.MigrateEssentialFiles
import com.ichi2.anki.servicelayer.scopedstorage.MoveConflictedFile
import com.ichi2.anki.servicelayer.scopedstorage.migrateuserdata.MigrateUserData
import com.ichi2.anki.utils.getUserFriendlyErrorText
import com.ichi2.compat.CompatHelper
import com.ichi2.preferences.getOrSetLong
import com.ichi2.utils.FileUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.File
import kotlin.math.max
import kotlin.properties.ReadOnlyProperty

// Shared preferences key for user-readable text representing migration error.
// If it is set, it means that media migration is ongoing, but currently paused due to an error.
const val PREF_MIGRATION_ERROR_TEXT = "migrationErrorText"

/**
 * A foreground service responsible for migrating the collection
 * from a public directory to an app-private directory.
 *
 * Notes on behavior:
 *
 *   * To show a progress bar, we first calculate the total size of the data to be transferred,
 *     and then, as the files are transferred by recursing into the directories,
 *     we add the size of each transferred file to a sum of transferred files.
 *     As the number of files and file sizes can change after the initial calculation,
 *     we can end up with the final ratio of transferred size to the estimate
 *     being less or greater to 1. This, however, is very unlikely, so we simply
 *     make sure than in the UI code transferred size never exceeds the estimate.
 *
 *   * As the app can be killed at any time, to make sure that the service shows consistent
 *     progress after it is restarted, we save the initial size of data to be transferred.
 *     When resuming migration, we can calculate transferred size
 *     by subtracting the size of remaining data from the stored value.
 *
 *   * We are not rate-limiting publication of the notifications in the code,
 *     as the files do not seem to be transferred so fast as to cause any problems.
 *     The system performs its own rate-limiting, dropping updates if they are published too quickly.
 *     An exception is is made for "completed progress notifications". See:
 *     https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/services/core/java/com/android/server/notification/NotificationManagerService.java
 *     https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/services/core/java/com/android/server/notification/RateEstimator.java
 *
 * TODO BEFORE-RELEASE Decide if this needs a wake lock.
 *   Copying files might take a long time.
 *   The user might decide to not use the phone for a while to let the migration run,
 *   expecting it to finish in an hour or two,
 *   only to find that during that time the migration has not progressed.
 *   A wake lock might make things proceed faster, but it also means more battery drain.
 */
class MigrationService : ServiceWithALifecycleScope(), ServiceWithASimpleBinder<MigrationService> {
    companion object {
        /**
         * Preference listing the total number of bytes that [MigrationService] expects to transfer.
         *
         * @see [MigrationService.getOrSetTotalTransferSize]
         */
        private const val TOTAL_BYTES_TO_TRANSFER_KEY: String = "migrationServiceTotalBytes"
    }

    sealed interface Progress {
        object CalculatingTransferSize : Progress

        data class Transferring(val transferredBytes: Long, val totalBytes: Long) : Progress {
            val ratio get() = if (totalBytes == 0L) 1f else transferredBytes.toFloat() / totalBytes
        }

        sealed interface Done : Progress
        object Success : Done
        data class Failure(val e: Exception) : Done
    }

    var flowOfProgress: MutableStateFlow<Progress> = MutableStateFlow(Progress.CalculatingTransferSize)

    private val preferences get() = AnkiDroidApp.getSharedPrefs(this)

    private lateinit var migrateUserDataTask: MigrateUserData

    private var serviceHasBeenStarted = false

    // To simplify things by allowing binding to the service at any time,
    // make sure the service has the correct progress emitted even if it is not going to be started.
    override fun onCreate() {
        if (getMediaMigrationState() is MediaMigrationState.NotOngoing.NotNeeded) {
            flowOfProgress.tryEmit(Progress.Success)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.w("onStartCommand(%s, ...)", intent)

        if (serviceHasBeenStarted) {
            Timber.w("MigrationService.onStartCommand has been called twice")
            return START_STICKY
        }

        serviceHasBeenStarted = true

        lifecycleScope.launch(Dispatchers.IO) {
            flowOfProgress.emit(Progress.CalculatingTransferSize)

            try {
                migrateUserDataTask = MigrateUserData.createInstance(preferences)

                val remainingTransferSize = getRemainingTransferSize(migrateUserDataTask)
                val totalBytesToTransfer = getOrSetTotalTransferSize(valueToPersistIfNotCalculated = remainingTransferSize)
                var transferredBytes = max(totalBytesToTransfer - remainingTransferSize, 0)

                migrateUserDataTask.migrateFiles(progressListener = { deltaTransferredBytes ->
                    transferredBytes += deltaTransferredBytes
                    flowOfProgress.tryEmit(
                        Progress.Transferring(
                            transferredBytes = transferredBytes.coerceIn(0, totalBytesToTransfer),
                            totalBytes = totalBytesToTransfer
                        )
                    )
                })

                // TODO BEFORE-RELEASE Consolidate setting/removing migration-related preferences.
                //   The existence of these determine if the *media* migration is taking place.
                //   These are currently set in MigrateEssentialFiles.updatePreferences
                //   on *background* thread, and removed here in another *background* thread.
                //   These are read from other threads, mostly via userMigrationIsInProgress,
                //   which might be a race condition and lead to subtle bugs.
                preferences.edit {
                    remove(PREF_MIGRATION_DESTINATION)
                    remove(PREF_MIGRATION_SOURCE)
                    remove(TOTAL_BYTES_TO_TRANSFER_KEY)
                }

                flowOfProgress.emit(Progress.Success)
            } catch (e: Exception) {
                CrashReportService.sendExceptionReport(e, "Storage migration failed")

                preferences.edit {
                    putString(PREF_MIGRATION_ERROR_TEXT, getUserFriendlyErrorText(e).toString())
                }

                flowOfProgress.emit(Progress.Failure(e))
            }
        }

        lifecycleScope.launch {
            flowOfProgress.collect { progress ->
                startForeground(2, makeMigrationProgressNotification(progress))

                if (progress is Progress.Done) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                        @Suppress("DEPRECATION")
                        stopForeground(false)
                    } else {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    }

                    stopSelf()

                    when (progress) {
                        is Progress.Success ->
                            AnkiDroidApp.instance.activityAgnosticDialogs
                                .showOrScheduleStorageMigrationSucceededDialog()
                        is Progress.Failure ->
                            AnkiDroidApp.instance.activityAgnosticDialogs
                                .showOrScheduleStorageMigrationFailedDialog(progress.e)
                    }
                }
            }
        }

        return START_STICKY
    }

    private fun getRemainingTransferSize(task: MigrateUserData): Long {
        val ignoredFiles = MigrateEssentialFiles.iterateEssentialFiles(task.source) +
            File(task.source.directory, MoveConflictedFile.CONFLICT_DIRECTORY)
        val ignoredSpace = ignoredFiles.sumOf { FileUtil.getSize(it) }
        val folderSize = FileUtil.DirectoryContentInformation.fromDirectory(task.source.directory).totalBytes
        val remainingSpaceToMigrate = folderSize - ignoredSpace
        Timber.d("folder size: %d, safe: %d, remaining: %d", folderSize, ignoredSpace, remainingSpaceToMigrate)
        return remainingSpaceToMigrate
    }

    /**
     * Returns the total number of bytes which the MigrationService expects to transfer
     * @param valueToPersistIfNotCalculated The value to save to storage if the transfer size has not previously been calculated
     */
    private fun getOrSetTotalTransferSize(valueToPersistIfNotCalculated: Long): Long {
        // The first time that this is accessed will be on the first run of the service, so calculate the remaining transfer size.
        // On subsequent runs, return the value we stored in Shared Preferences
        return AnkiDroidApp.getSharedPrefs(this)
            .getOrSetLong(TOTAL_BYTES_TO_TRANSFER_KEY) { valueToPersistIfNotCalculated }
    }

    override fun onBind(intent: Intent) = SimpleBinder(this)

    /**
     * A file was expected at the provided location, but wasn't found
     * If it exists in the old location, attempt to migrate it.
     * Block until migrated.
     *
     * @return Whether the migration was successful (or unnecessary)
     */
    fun migrateFileImmediately(expectedFileLocation: File): Boolean {
        try {
            migrateUserDataTask.migrateFileImmediately(expectedFileLocation)
        } catch (e: Exception) {
            Timber.w(e, "Failed to migrate file")
        }
        return expectedFileLocation.exists()
    }
}

private fun Context.makeMigrationProgressNotification(progress: MigrationService.Progress): Notification {
    val builder = NotificationCompat.Builder(this, Channel.SCOPED_STORAGE_MIGRATION.id)
        .setSmallIcon(R.drawable.ic_star_notify)
        .setContentTitle(getString(R.string.migration__migrating_data))
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setSilent(true)

    when (progress) {
        is MigrationService.Progress.CalculatingTransferSize -> {
            builder.setOngoing(true)
            builder.setProgress(0, 0, true)
            builder.setContentText(getString(R.string.migration__calculating_transfer_size))
        }

        is MigrationService.Progress.Transferring -> {
            val transferredSizeText = Formatter.formatShortFileSize(this, progress.transferredBytes)
            val totalSizeText = Formatter.formatShortFileSize(this, progress.totalBytes)

            builder.setOngoing(true)
            builder.setProgress(Int.MAX_VALUE, (progress.ratio * Int.MAX_VALUE).toInt(), false)
            builder.setContentText(getString(R.string.migration__transferred_x_of_y, transferredSizeText, totalSizeText))
        }

        is MigrationService.Progress.Success -> {
            builder.setProgress(100, 100, false)
            builder.setContentText(getString(R.string.migration_successful_message))
        }

        // A note on behavior of BigTextStyle.
        // When the notification is collapsed, big text style is completely ignored,
        // and the notification builder's title and text is shown, single-line each:
        //
        //   Content title, bold
        //   Content text, ellipsized if long...
        //
        // When expanded, these are replaced by big content style's big content title
        // and big text. If big content title is not present, notification's content title is used:
        //
        //   Big content title or notification's content title, bold
        //   Big text, spanning several lines
        //   if it is sufficiently long
        is MigrationService.Progress.Failure -> {
            val errorText = getUserFriendlyErrorText(progress.e)

            val copyDebugInfoIntent = IntentHandler
                .copyStringToClipboardIntent(this, progress.e.stackTraceToString())
            val copyDebugInfoPendingIntent = CompatHelper.compat
                .getImmutableActivityIntent(this, 1, copyDebugInfoIntent, 0)

            val helpUrl = getString(R.string.migration_failed_help_url)
            val viewHelpUrlIntent = Intent(Intent.ACTION_VIEW, Uri.parse(helpUrl))
            val viewHelpUrlPendingIntent = CompatHelper.compat
                .getImmutableActivityIntent(this, 0, viewHelpUrlIntent, 0)

            builder.setContentTitle(getString(R.string.migration__failed__title))
            builder.setContentText(errorText)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(errorText))
            builder.addAction(R.drawable.ic_star_notify, getString(R.string.feedback_copy_debug), copyDebugInfoPendingIntent)
            builder.addAction(0, getString(R.string.help), viewHelpUrlPendingIntent)
        }
    }

    return builder.build()
}

/**
 * A delegate for a property that yields:
 *   * the [MigrationService] if media migration is currently ongoing and not paused,
 *     and when the owner is started,
 *   * or `null` otherwise.
 *
 * Note: binding to the service happens fast, but not immediately,
 * so expect this property to be `null` when reading right after `onStart()`.
 */
fun <O> O.migrationServiceWhileStartedOrNull(): ReadOnlyProperty<Any?, MigrationService?>
        where O : Context, O : LifecycleOwner {
    var service: MigrationService? = null

    lifecycleScope.launch {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (getMediaMigrationState() is MediaMigrationState.Ongoing.NotPaused) {
                try {
                    withBoundTo<MigrationService> {
                        service = it
                        suspendCancellableCoroutine {}
                    }
                } finally {
                    service = null
                }
            }
        }
    }

    return ReadOnlyProperty { _, _ -> service }
}

/**************************************************************************************************/

/**
 * This represents the overarching state of media migration as determined by:
 *   * the build/flavor,
 *   * the API level,
 *   * some settings persisted in shared preferences.
 *
 * This is not determined by permissions or static variables.
 */
sealed interface MediaMigrationState {
    sealed interface NotOngoing : MediaMigrationState {
        sealed interface NotNeeded : NotOngoing {
            object CollectionIsInAppPrivateFolder : NotNeeded
            object CollectionIsInPublicFolderButWillRemainAccessible : NotNeeded
        }
        object Needed : NotOngoing
    }

    sealed interface Ongoing : MediaMigrationState {
        object NotPaused : Ongoing
        class PausedDueToError(val errorText: String) : Ongoing
    }
}

// TODO Consider refactoring ScopedStorageService to remove its methods used here,
//   inlining them, and use this method throughout the app for media migration state.
fun Context.getMediaMigrationState(): MediaMigrationState {
    val preferences = AnkiDroidApp.getSharedPrefs(this)

    fun migrationIsOngoing() = ScopedStorageService.mediaMigrationIsInProgress(preferences)
    fun collectionIsInAppPrivateDirectory() = !ScopedStorageService.isLegacyStorage(this)
    fun collectionWillRemainAccessibleAfterReinstall() =
        !ScopedStorageService.collectionWillBeMadeInaccessibleAfterUninstall(this)

    return if (migrationIsOngoing()) {
        val errorText = preferences.getString(PREF_MIGRATION_ERROR_TEXT, null)
        when {
            errorText.isNullOrBlank() ->
                MediaMigrationState.Ongoing.NotPaused
            else ->
                MediaMigrationState.Ongoing.PausedDueToError(errorText)
        }
    } else {
        when {
            collectionIsInAppPrivateDirectory() ->
                MediaMigrationState.NotOngoing.NotNeeded.CollectionIsInAppPrivateFolder
            collectionWillRemainAccessibleAfterReinstall() ->
                MediaMigrationState.NotOngoing.NotNeeded.CollectionIsInPublicFolderButWillRemainAccessible
            else ->
                MediaMigrationState.NotOngoing.Needed
        }
    }
}
