package com.simplemobiletools.gallery.pro.helpers

import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import android.text.format.DateFormat
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.gallery.pro.R
import com.simplemobiletools.gallery.pro.extensions.*
import com.simplemobiletools.gallery.pro.models.Medium
import com.simplemobiletools.gallery.pro.models.ThumbnailItem
import com.simplemobiletools.gallery.pro.models.ThumbnailSection
import java.io.File
import java.util.*

class MediaFetcher(val context: Context) {
    var shouldStop = false

    fun getFilesFrom(curPath: String, isPickImage: Boolean, isPickVideo: Boolean, getProperDateTaken: Boolean, getProperFileSize: Boolean,
                     favoritePaths: ArrayList<String>, getVideoDurations: Boolean, sortMedia: Boolean = true): ArrayList<Medium> {
        val filterMedia = context.config.filterMedia
        if (filterMedia == 0) {
            return ArrayList()
        }

        val curMedia = ArrayList<Medium>()
        val newMedia = getMediaInFolder(curPath, isPickImage, isPickVideo, filterMedia, getProperDateTaken, getProperFileSize, favoritePaths, getVideoDurations)
        curMedia.addAll(newMedia)

        if (sortMedia) {
            sortMedia(curMedia, context.config.getFileSorting(curPath))
        }

        return curMedia
    }

    fun getFoldersToScan(): ArrayList<String> {
        val filterMedia = context.config.filterMedia
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        val uri = MediaStore.Files.getContentUri("external")

        val selection = "${getSelectionQuery(filterMedia)} ${MediaStore.Images.ImageColumns.BUCKET_ID} IS NOT NULL) GROUP BY (${MediaStore.Images.ImageColumns.BUCKET_ID}"
        val selectionArgs = getSelectionArgsQuery(filterMedia).toTypedArray()

        return try {
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            parseCursor(cursor)
        } catch (e: Exception) {
            ArrayList()
        }
    }

    private fun getSelectionQuery(filterMedia: Int): String {
        val query = StringBuilder()
        query.append("(")
        if (filterMedia and TYPE_IMAGES != 0) {
            photoExtensions.forEach {
                query.append("${MediaStore.Images.Media.DATA} LIKE ? OR ")
            }
        }

        if (filterMedia and TYPE_VIDEOS != 0) {
            videoExtensions.forEach {
                query.append("${MediaStore.Images.Media.DATA} LIKE ? OR ")
            }
        }

        if (filterMedia and TYPE_GIFS != 0) {
            query.append("${MediaStore.Images.Media.DATA} LIKE ? OR ")
        }

        if (filterMedia and TYPE_RAWS != 0) {
            rawExtensions.forEach {
                query.append("${MediaStore.Images.Media.DATA} LIKE ? OR ")
            }
        }

        if (filterMedia and TYPE_SVGS != 0) {
            query.append("${MediaStore.Images.Media.DATA} LIKE ? OR ")
        }

        var selectionQuery = query.toString().trim().removeSuffix("OR")
        selectionQuery += ") AND "
        return selectionQuery
    }

    private fun getSelectionArgsQuery(filterMedia: Int): ArrayList<String> {
        val args = ArrayList<String>()
        if (filterMedia and TYPE_IMAGES != 0) {
            photoExtensions.forEach {
                args.add("%$it")
            }
        }

        if (filterMedia and TYPE_VIDEOS != 0) {
            videoExtensions.forEach {
                args.add("%$it")
            }
        }

        if (filterMedia and TYPE_GIFS != 0) {
            args.add("%.gif")
        }

        if (filterMedia and TYPE_RAWS != 0) {
            rawExtensions.forEach {
                args.add("%$it")
            }
        }

        if (filterMedia and TYPE_SVGS != 0) {
            args.add("%.svg")
        }

        return args
    }

    private fun parseCursor(cursor: Cursor): ArrayList<String> {
        val foldersToIgnore = arrayListOf("/storage/emulated/legacy")
        val config = context.config
        val includedFolders = config.includedFolders
        var foldersToScan = config.everShownFolders.filter { it == FAVORITES || it == RECYCLE_BIN || File(it).exists() }.toMutableList() as ArrayList

        cursor.use {
            if (cursor.moveToFirst()) {
                do {
                    val path = cursor.getStringValue(MediaStore.Images.Media.DATA)
                    val parentPath = File(path).parent ?: continue
                    if (!includedFolders.contains(parentPath) && !foldersToIgnore.contains(parentPath)) {
                        foldersToScan.add(parentPath)
                    }
                } while (cursor.moveToNext())
            }
        }

        includedFolders.forEach {
            addFolder(foldersToScan, it)
        }

        val showHidden = config.shouldShowHidden
        val excludedFolders = config.excludedFolders
        foldersToScan = foldersToScan.filter { it.shouldFolderBeVisible(excludedFolders, includedFolders, showHidden) } as ArrayList<String>
        return foldersToScan.distinctBy { it.getDistinctPath() } as ArrayList<String>
    }

    private fun addFolder(curFolders: ArrayList<String>, folder: String) {
        curFolders.add(folder)
        val files = File(folder).listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                addFolder(curFolders, file.absolutePath)
            }
        }
    }

    private fun getMediaInFolder(folder: String, isPickImage: Boolean, isPickVideo: Boolean, filterMedia: Int, getProperDateTaken: Boolean,
                                 getProperFileSize: Boolean, favoritePaths: ArrayList<String>, getVideoDurations: Boolean): ArrayList<Medium> {
        val media = ArrayList<Medium>()

        val isRecycleBin = folder == RECYCLE_BIN
        val deletedMedia = if (isRecycleBin) {
            context.getUpdatedDeletedMedia(context.galleryDB.MediumDao())
        } else {
            ArrayList()
        }

        val config = context.config
        val checkProperFileSize = getProperFileSize || config.fileLoadingPriority == PRIORITY_COMPROMISE
        val checkFileExistence = config.fileLoadingPriority == PRIORITY_VALIDITY
        val showHidden = config.shouldShowHidden
        val dateTakens = if (getProperDateTaken && folder != FAVORITES && !isRecycleBin) getFolderDateTakens(folder) else HashMap()

        val files = when (folder) {
            FAVORITES -> favoritePaths.filter { showHidden || !it.contains("/.") }.map { File(it) }.toTypedArray()
            RECYCLE_BIN -> deletedMedia.map { File(it.path) }.toTypedArray()
            else -> File(folder).listFiles() ?: return media
        }

        for (file in files) {
            if (shouldStop) {
                break
            }

            val path = file.absolutePath
            val isImage = path.isImageFast()
            val isVideo = if (isImage) false else path.isVideoFast()
            val isGif = if (isImage || isVideo) false else path.isGif()
            val isRaw = if (isImage || isVideo || isGif) false else path.isRawFast()
            val isSvg = if (isImage || isVideo || isGif || isRaw) false else path.isSvg()

            if (!isImage && !isVideo && !isGif && !isRaw && !isSvg)
                continue

            if (isVideo && (isPickImage || filterMedia and TYPE_VIDEOS == 0))
                continue

            if (isImage && (isPickVideo || filterMedia and TYPE_IMAGES == 0))
                continue

            if (isGif && filterMedia and TYPE_GIFS == 0)
                continue

            if (isRaw && filterMedia and TYPE_RAWS == 0)
                continue

            if (isSvg && filterMedia and TYPE_SVGS == 0)
                continue

            val filename = file.name
            if (!showHidden && filename.startsWith('.'))
                continue

            val size = if (checkProperFileSize || checkFileExistence) file.length() else 1L
            if ((checkProperFileSize || checkFileExistence) && size <= 0L) {
                continue
            }

            if (checkFileExistence && !file.exists()) {
                continue
            }

            if (isRecycleBin) {
                deletedMedia.firstOrNull { it.path == path }?.apply {
                    media.add(this)
                }
            } else {
                val lastModified = file.lastModified()
                var dateTaken = lastModified
                val videoDuration = if (getVideoDurations && isVideo) path.getVideoDuration() else 0

                if (getProperDateTaken) {
                    dateTaken = dateTakens.remove(filename) ?: lastModified
                }

                val type = when {
                    isVideo -> TYPE_VIDEOS
                    isGif -> TYPE_GIFS
                    isRaw -> TYPE_RAWS
                    isSvg -> TYPE_SVGS
                    else -> TYPE_IMAGES
                }

                val isFavorite = favoritePaths.contains(path)
                val medium = Medium(null, filename, path, file.parent, lastModified, dateTaken, size, type, videoDuration, isFavorite, 0L)
                media.add(medium)
            }
        }
        return media
    }

    private fun getFolderDateTakens(folder: String): HashMap<String, Long> {
        val projection = arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN
        )

        val uri = MediaStore.Files.getContentUri("external")
        val selection = "${MediaStore.Images.Media.DATA} LIKE ? AND ${MediaStore.Images.Media.DATA} NOT LIKE ?"
        val selectionArgs = arrayOf("$folder/%", "$folder/%/%")

        val dateTakens = HashMap<String, Long>()
        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            if (cursor.moveToFirst()) {
                do {
                    try {
                        val path = cursor.getStringValue(MediaStore.Images.Media.DISPLAY_NAME)
                        val dateTaken = cursor.getLongValue(MediaStore.Images.Media.DATE_TAKEN)
                        dateTakens[path] = dateTaken
                    } catch (e: Exception) {
                    }
                } while (cursor.moveToNext())
            }
        }

        return dateTakens
    }

    fun sortMedia(media: ArrayList<Medium>, sorting: Int) {
        if (sorting and SORT_BY_RANDOM != 0) {
            media.shuffle()
            return
        }

        media.sortWith(Comparator { o1, o2 ->
            o1 as Medium
            o2 as Medium
            var result = when {
                sorting and SORT_BY_NAME != 0 -> {
                    if (sorting and SORT_USE_NUMERIC_VALUE != 0) {
                        AlphanumericComparator().compare(o1.name.toLowerCase(), o2.name.toLowerCase())
                    } else {
                        o1.name.toLowerCase().compareTo(o2.name.toLowerCase())
                    }
                }
                sorting and SORT_BY_PATH != 0 -> {
                    if (sorting and SORT_USE_NUMERIC_VALUE != 0) {
                        AlphanumericComparator().compare(o1.path.toLowerCase(), o2.path.toLowerCase())
                    } else {
                        o1.path.toLowerCase().compareTo(o2.path.toLowerCase())
                    }
                }
                sorting and SORT_BY_SIZE != 0 -> o1.size.compareTo(o2.size)
                sorting and SORT_BY_DATE_MODIFIED != 0 -> o1.modified.compareTo(o2.modified)
                else -> o1.taken.compareTo(o2.taken)
            }

            if (sorting and SORT_DESCENDING != 0) {
                result *= -1
            }
            result
        })
    }

    fun groupMedia(media: ArrayList<Medium>, path: String): ArrayList<ThumbnailItem> {
        val mediumGroups = LinkedHashMap<String, ArrayList<Medium>>()
        val pathToCheck = if (path.isEmpty()) SHOW_ALL else path
        val currentGrouping = context.config.getFolderGrouping(pathToCheck)
        if (currentGrouping and GROUP_BY_NONE != 0) {
            return media as ArrayList<ThumbnailItem>
        }

        val thumbnailItems = ArrayList<ThumbnailItem>()
        if (context.config.scrollHorizontally) {
            media.mapTo(thumbnailItems) { it }
            return thumbnailItems
        }

        media.forEach {
            val key = it.getGroupingKey(currentGrouping)
            if (!mediumGroups.containsKey(key)) {
                mediumGroups[key] = ArrayList()
            }
            mediumGroups[key]!!.add(it)
        }

        val sortDescending = currentGrouping and GROUP_DESCENDING != 0
        val sorted = if (currentGrouping and GROUP_BY_LAST_MODIFIED_DAILY != 0 || currentGrouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0 ||
                currentGrouping and GROUP_BY_DATE_TAKEN_DAILY != 0 || currentGrouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0) {
            mediumGroups.toSortedMap(if (sortDescending) compareByDescending {
                it.toLongOrNull() ?: 0L
            } else {
                compareBy { it.toLongOrNull() ?: 0L }
            })
        } else {
            mediumGroups.toSortedMap(if (sortDescending) compareByDescending { it } else compareBy { it })
        }

        mediumGroups.clear()
        for ((key, value) in sorted) {
            mediumGroups[key] = value
        }

        val today = formatDate(System.currentTimeMillis().toString(), true)
        val yesterday = formatDate((System.currentTimeMillis() - DAY_SECONDS * 1000).toString(), true)
        for ((key, value) in mediumGroups) {
            val sectionKey = getFormattedKey(key, currentGrouping, today, yesterday)
            thumbnailItems.add(ThumbnailSection(sectionKey))
            thumbnailItems.addAll(value)
        }

        return thumbnailItems
    }

    private fun getFormattedKey(key: String, grouping: Int, today: String, yesterday: String): String {
        var result = when {
            grouping and GROUP_BY_LAST_MODIFIED_DAILY != 0 || grouping and GROUP_BY_DATE_TAKEN_DAILY != 0 -> getFinalDate(formatDate(key, true), today, yesterday)
            grouping and GROUP_BY_LAST_MODIFIED_MONTHLY != 0 || grouping and GROUP_BY_DATE_TAKEN_MONTHLY != 0 -> formatDate(key, false)
            grouping and GROUP_BY_FILE_TYPE != 0 -> getFileTypeString(key)
            grouping and GROUP_BY_EXTENSION != 0 -> key.toUpperCase()
            grouping and GROUP_BY_FOLDER != 0 -> context.humanizePath(key)
            else -> key
        }

        if (result.isEmpty()) {
            result = context.getString(R.string.unknown)
        }

        return result
    }

    private fun getFinalDate(date: String, today: String, yesterday: String): String {
        return when (date) {
            today -> context.getString(R.string.today)
            yesterday -> context.getString(R.string.yesterday)
            else -> date
        }
    }

    private fun formatDate(timestamp: String, showDay: Boolean): String {
        return if (timestamp.areDigitsOnly()) {
            val cal = Calendar.getInstance(Locale.ENGLISH)
            cal.timeInMillis = timestamp.toLong()
            val format = if (showDay) "dd MMM yyyy" else "MMM yyyy"
            DateFormat.format(format, cal).toString()
        } else {
            ""
        }
    }

    private fun getFileTypeString(key: String): String {
        val stringId = when (key.toInt()) {
            TYPE_IMAGES -> R.string.images
            TYPE_VIDEOS -> R.string.videos
            TYPE_GIFS -> R.string.gifs
            TYPE_RAWS -> R.string.raw_images
            else -> R.string.svgs
        }
        return context.getString(stringId)
    }
}
