/*
 *     Copyright (C) 2024 Akane Foundation
 *
 *     Gramophone is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Gramophone is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.akanework.gramophone.logic.utils

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import androidx.core.database.getStringOrNull
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.akanework.gramophone.R
import org.akanework.gramophone.ui.viewmodels.LibraryViewModel
import java.io.File
import java.lang.IllegalArgumentException
import java.time.Instant
import java.time.ZoneId


/**
 * [MediaStoreUtils] contains all the methods for reading
 * from mediaStore.
 */
object MediaStoreUtils {

    interface Item {
        val id: Long?
        val title: String?
        val songList: MutableList<MediaItem>
    }

    /**
     * [Album] stores Album metadata.
     */
    data class Album(
        override val id: Long?,
        override val title: String?,
        val artist: String?,
        val albumYear: Int?,
        override val songList: MutableList<MediaItem>,
    ) : Item

    /**
     * [Artist] stores Artist metadata.
     */
    data class Artist(
        override val id: Long?,
        override val title: String?,
        override val songList: MutableList<MediaItem>,
        val albumList: MutableList<Album>,
    ) : Item

    /**
     * [Genre] stores Genre metadata.
     */
    data class Genre(
        override val id: Long?,
        override val title: String?,
        override val songList: MutableList<MediaItem>,
    ) : Item

    /**
     * [Date] stores Date metadata.
     */
    data class Date(
        override val id: Long,
        override val title: String?,
        override val songList: MutableList<MediaItem>,
    ) : Item

    /**
     * [Playlist] stores playlist information.
     */
    open class Playlist(
        override val id: Long,
        override var title: String?,
        override val songList: MutableList<MediaItem>
    ) : Item

    @Parcelize
    data class Lyric(
        val timeStamp: Long = 0,
        val content: String = "",
        val isTranslation: Boolean = false
    ) : Parcelable

    class RecentlyAdded(id: Long, songList: List<MediaItem>) : Playlist(
        id, null, songList
            .sortedByDescending { it.mediaMetadata.extras?.getLong("AddDate") ?: 0 }.toMutableList()
    ) {
        private val rawList: List<MediaItem> = super.songList
        private var filteredList: List<MediaItem>? = null
        var minAddDate: Long = 0
            set(value) {
                if (field != value) {
                    field = value
                    filteredList = null
                }
            }
        override val songList: MutableList<MediaItem>
            get() {
                if (filteredList == null) {
                    filteredList = rawList.filter {
                        (it.mediaMetadata.extras?.getLong("AddDate") ?: 0) >= minAddDate
                    }
                }
                return filteredList!!.toMutableList()
            }
    }

    /**
     * [LibraryStoreClass] collects above metadata classes
     * together for more convenient reading/writing.
     */
    data class LibraryStoreClass(
        val songList: MutableList<MediaItem>,
        val albumList: MutableList<Album>,
        val albumArtistList: MutableList<Artist>,
        val artistList: MutableList<Artist>,
        val genreList: MutableList<Genre>,
        val dateList: MutableList<Date>,
        val playlistList: MutableList<Playlist>,
        val folderStructure: FileNode,
        val shallowFolder: FileNode,
        val folders: Set<String>
    )

    data class FileNode(
        val folderName: String,
        val folderList: HashMap<String, FileNode>,
        val songList: MutableList<MediaItem>,
    )

    private fun handleMediaFolder(path: String, rootNode: FileNode): FileNode {
        var node: FileNode = rootNode
        for (fld in path.substring(1).split('/')) {
            var newNode = node.folderList[fld]
            if (newNode == null) {
                newNode = FileNode(folderName = fld, hashMapOf(), mutableListOf())
                node.folderList[newNode.folderName] = newNode
            }
            node = newNode
        }
        return node
    }

    private fun handleShallowMediaItem(
        mediaItem: MediaItem,
        path: String,
        shallowFolder: FileNode,
        folderArray: MutableList<String>
    ) {
        val splitPath = path.split('/')
        if (splitPath.size < 2) throw IllegalArgumentException("splitPath.size < 2: $path")
        val lastFolderName = splitPath[splitPath.size - 2]
        var folder = shallowFolder.folderList[lastFolderName]
        if (folder == null) {
            folder = FileNode(folderName = lastFolderName, hashMapOf(), mutableListOf())
            shallowFolder.folderList[folder.folderName] = folder
            // hack to cut off /
            folderArray.add(
                path.substring(0, splitPath[splitPath.size - 1].length + 1)
            )
        }
        folder.songList.add(mediaItem)
    }

    private val formatCollection = mutableListOf(
        "audio/x-wav",
        "audio/ogg",
        "audio/aac",
        "audio/midi"
    )

    /**
     * [getAllSongs] gets all of your songs from your local disk.
     *
     * @param context
     * @return
     */
    private fun getAllSongs(context: Context): LibraryStoreClass {
        val folderArray: MutableList<String> = mutableListOf()
        var selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        for (i in formatCollection) {
            selection = "$selection or ${MediaStore.Audio.Media.MIME_TYPE} = '$i'"
        }
        val projection =
            arrayListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ARTIST_ID,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ARTIST,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATE_MODIFIED
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    add(MediaStore.Audio.Media.GENRE)
                    add(MediaStore.Audio.Media.GENRE_ID)
                    add(MediaStore.Audio.Media.CD_TRACK_NUMBER)
                    add(MediaStore.Audio.Media.COMPILATION)
                    add(MediaStore.Audio.Media.COMPOSER)
                    add(MediaStore.Audio.Media.DATE_TAKEN)
                    add(MediaStore.Audio.Media.WRITER)
                    add(MediaStore.Audio.Media.DISC_NUMBER)
                    add(MediaStore.Audio.Media.AUTHOR)
                }
            }.toTypedArray()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val limitValue = prefs.getInt(
            "mediastore_filter",
            context.resources.getInteger(R.integer.filter_default_sec)
        )
        val folderFilter = prefs.getStringSet("folderFilter", setOf()) ?: setOf()
        val folders = hashSetOf<String>()
        val root = FileNode(folderName = "storage", hashMapOf(), mutableListOf())
        val shallowRoot = FileNode(folderName = "shallow", hashMapOf(), mutableListOf())

        // Initialize list and maps.
        val songs = mutableListOf<MediaItem>()
        val albumMap = hashMapOf<Long?, Album>()
        val artistMap = hashMapOf<Long?, Artist>()
        val artistCacheMap = hashMapOf<String?, Long?>()
        val albumArtistMap = hashMapOf<String?, MutableList<MediaItem>>()
        val genreMap = hashMapOf<Long?, Genre>()
        val dateMap = hashMapOf<Int?, Date>()
        val cursor =
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                null,
            )

        cursor?.use {
            // Get columns from mediaStore.
            val idColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumArtistColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
            val pathColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val yearColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val albumIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val artistIdColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
            val mimeTypeColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val discNumberColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                it.getColumnIndex(MediaStore.Audio.Media.DISC_NUMBER) else null
            val trackNumberColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val genreColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE) else null
            val genreIdColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE_ID) else null
            val cdTrackNumberColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.CD_TRACK_NUMBER) else null
            val compilationColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.COMPILATION) else null
            val dateTakenColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_TAKEN) else null
            val composerColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.COMPOSER) else null
            val writerColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.WRITER) else null
            val authorColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                it.getColumnIndexOrThrow(MediaStore.Audio.Media.AUTHOR) else null
            val durationColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val addDateColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val modifiedDateColumn = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)

            // Base URI for artwork
            val artworkUri = Uri.parse("content://media/external/audio/albumart")

            while (it.moveToNext()) {
                val duration = it.getLong(durationColumn)
                // If duration does not path our filter value, instantly proceed with next song
                if (duration < limitValue * 1000) continue
                // If folder is blacklisted, don't even bother loading other information
                val path = it.getString(pathColumn)
                val fldPath = path.substringBeforeLast('/')
                if (folderFilter.contains(fldPath)) continue
                val id = it.getLong(idColumn)
                val title = it.getString(titleColumn)
                val artist = it.getString(artistColumn)
                    .let { v -> if (v == "<unknown>") null else v }
                val album = it.getStringOrNull(albumColumn)
                val albumArtist =
                    it.getString(albumArtistColumn)
                        ?: null
                val year = it.getInt(yearColumn).let { v -> if (v == 0) null else v }
                val albumId = it.getLong(albumIdColumn)
                val artistId = it.getLong(artistIdColumn)
                val mimeType = it.getString(mimeTypeColumn)
                var discNumber = discNumberColumn?.let { col -> it.getInt(col) }
                var trackNumber = it.getInt(trackNumberColumn)
                val cdTrackNumber = cdTrackNumberColumn?.let { col -> it.getStringOrNull(col) }
                val compilation = compilationColumn?.let { col -> it.getStringOrNull(col) }
                val dateTaken = dateTakenColumn?.let { col -> it.getStringOrNull(col) }
                val composer = composerColumn?.let { col -> it.getStringOrNull(col) }
                val writer = writerColumn?.let { col -> it.getStringOrNull(col) }
                val author = authorColumn?.let { col -> it.getStringOrNull(col) }
                val genre = genreColumn?.let { col -> it.getStringOrNull(col) }
                val genreId = genreIdColumn?.let { col -> it.getLong(col) }
                val addDate = it.getLong(addDateColumn)
                val modifiedDate = it.getLong(modifiedDateColumn)
                val dateTakenParsed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // the column exists since R, so we can always use these APIs
                    dateTaken?.toLongOrNull()?.let { it1 -> Instant.ofEpochMilli(it1) }
                        ?.atZone(ZoneId.systemDefault())
                } else null
                val dateTakenYear = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    dateTakenParsed?.year
                } else null
                val dateTakenMonth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    dateTakenParsed?.monthValue
                } else null
                val dateTakenDay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    dateTakenParsed?.dayOfMonth
                } else null

                // Since we're using glide, we can get album cover with a uri.
                val imgUri =
                    ContentUris.withAppendedId(
                        artworkUri,
                        albumId,
                    )
                // Process track numbers that have disc number added on.
                // e.g. 1001 - Disc 01, Track 01.
                if (trackNumber >= 1000) {
                    discNumber = trackNumber / 1000
                    trackNumber %= 1000
                }

                // Build our mediaItem.
                val song = MediaItem
                    .Builder()
                    .setUri(Uri.fromFile(File(path)))
                    .setMediaId(id.toString())
                    .setMimeType(mimeType)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .setTitle(title)
                            .setWriter(writer)
                            .setCompilation(compilation)
                            .setComposer(composer)
                            .setArtist(artist)
                            .setAlbumTitle(album)
                            .setAlbumArtist(albumArtist)
                            .setArtworkUri(imgUri)
                            .setTrackNumber(trackNumber)
                            .setDiscNumber(discNumber)
                            .setGenre(genre)
                            .setRecordingDay(dateTakenDay)
                            .setRecordingMonth(dateTakenMonth)
                            .setRecordingYear(dateTakenYear)
                            .setReleaseYear(year)
                            .setExtras(Bundle().apply {
                                putLong("ArtistId", artistId)
                                putLong("AlbumId", albumId)
                                if (genreId != null) {
                                    putLong("GenreId", genreId)
                                }
                                putString("Author", author)
                                putLong("AddDate", addDate)
                                putLong("Duration", duration)
                                putLong("ModifiedDate", modifiedDate)
                                putString("MimeType", mimeType)
                                cdTrackNumber?.toIntOrNull()
                                    ?.let { it1 -> putInt("CdTrackNumber", it1) }
                            })
                            .build(),
                    ).build()
                songs.add(song)

                // Build our metadata maps/lists.
                artistMap.getOrPut(artistId) {
                    Artist(artistId, artist, mutableListOf(), mutableListOf())
                }.songList.add(song)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    artistCacheMap.putIfAbsent(artist, artistId)
                } else {
                    // meh...
                    if (!artistCacheMap.containsKey(artist))
                        artistCacheMap[artist] = artistId
                }
                albumMap.getOrPut(albumId) {
                    Album(albumId, album, albumArtist ?: artist, year, mutableListOf())
                }.songList.add(song)
                albumArtistMap.getOrPut(albumArtist) { mutableListOf() }.add(song)
                genreMap.getOrPut(genreId) { Genre(genreId, genre, mutableListOf()) }.songList.add(song)
                dateMap.getOrPut(year) {Date(year?.toLong() ?: 0, year?.toString(), mutableListOf()) }.songList.add(song)
                handleMediaFolder(path.toString(), root).songList.add(song)
                handleShallowMediaItem(song, path.toString(), shallowRoot, folderArray)
                folders.add(fldPath)
            }
        }

        // Parse all the lists.
        val albumList = albumMap.values.toMutableList()
        albumList.forEach {
            artistMap[artistCacheMap[it.artist]]?.albumList?.add(it)
        }
        val artistList = artistMap.values.toMutableList()
        val albumArtistList = albumArtistMap.entries.map { (cat, songs) ->
            // we do not get unique IDs for album artists, so just take first match :shrug:
            val at = artistList.find { it.title == cat }
            Artist(at?.id, cat, songs, at?.albumList ?: mutableListOf())
        }.toMutableList()
        val genreList = genreMap.values.toMutableList()
        val dateList = dateMap.values.toMutableList()

        return LibraryStoreClass(
            songs,
            albumList,
            albumArtistList,
            artistList,
            genreList,
            dateList,
            getPlaylists(context, songs),
            root,
            shallowRoot,
            folders
        )
    }

    /**
     * Retrieves a list of playlists with their associated songs.
     */
    private fun getPlaylists(
        context: Context,
        songList: MutableList<MediaItem>
    ): MutableList<Playlist> {
        val playlists = mutableListOf<Playlist>()
        playlists.add(
            RecentlyAdded(
                -1,
                songList.toMutableList()
            )
                .apply {
                    // TODO setting?
                    minAddDate = (System.currentTimeMillis() / 1000) - (2 * 7 * 24 * 60 * 60)
                })

        // Define the content resolver
        val contentResolver: ContentResolver = context.contentResolver

        // Define the URI for playlists
        val playlistUri: Uri =
            @Suppress("DEPRECATION") MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI

        // Define the projection (columns to retrieve)
        val projection = arrayOf(
            @Suppress("DEPRECATION") MediaStore.Audio.Playlists._ID,
            @Suppress("DEPRECATION") MediaStore.Audio.Playlists.NAME
        )

        // Query the playlists
        val cursor = contentResolver.query(playlistUri, projection, null, null, null)

        cursor?.use {
            while (it.moveToNext()) {
                val playlistId = it.getLong(
                    it.getColumnIndexOrThrow(
                        @Suppress("DEPRECATION") MediaStore.Audio.Playlists._ID
                    )
                )
                val playlistName = it.getString(
                    it.getColumnIndexOrThrow(
                        @Suppress("DEPRECATION") MediaStore.Audio.Playlists.NAME
                    )
                ).ifEmpty { null }

                // Retrieve the list of songs for each playlist
                val songs = getSongsInPlaylist(contentResolver, playlistId, songList)

                // Create a Playlist object and add it to the list
                val playlist = Playlist(playlistId, playlistName, songs.toMutableList())
                playlists.add(playlist)
            }
        }

        cursor?.close()

        if (playlists.none { it.title == "gramophone_favourite" }) {
            val values = ContentValues()
            @Suppress("DEPRECATION")
            values.put(MediaStore.Audio.Playlists.NAME, "gramophone_favourite")

            @Suppress("DEPRECATION")
            values.put(MediaStore.Audio.Playlists.DATE_ADDED, System.currentTimeMillis())

            values.put(
                @Suppress("DEPRECATION")
                MediaStore.Audio.Playlists.DATE_MODIFIED,
                System.currentTimeMillis()
            )


            val resolver: ContentResolver = contentResolver

            @Suppress("DEPRECATION")
            val favPlaylistUri =
                resolver.insert(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, values)
            if (favPlaylistUri != null) {
                val playlistId = favPlaylistUri.lastPathSegment!!.toLong()
                playlists.add(
                    Playlist(
                        playlistId,
                        context.getString(R.string.playlist_favourite),
                        mutableListOf()
                    )
                )
            }
        } else {
            playlists.first { it.title == "gramophone_favourite" }.title =
                context.getString(R.string.playlist_favourite)
        }

        return playlists
    }

    /**
     * Retrieves the list of songs in a playlist.
     */
    private fun getSongsInPlaylist(
        contentResolver: ContentResolver,
        playlistId: Long,
        songList: MutableList<MediaItem>
    ): List<MediaItem> {
        val songs = mutableListOf<MediaItem>()

        // Define the URI for playlist members (songs in the playlist)
        val uri = @Suppress("DEPRECATION") MediaStore.Audio
            .Playlists.Members.getContentUri("external", playlistId)

        // Define the projection (columns to retrieve)
        val projection = arrayOf(
            @Suppress("DEPRECATION") MediaStore.Audio.Playlists.Members.AUDIO_ID,
        )

        // Query the songs in the playlist
        val cursor = contentResolver.query(
            uri, projection, null, null,
            @Suppress("DEPRECATION") MediaStore.Audio.Playlists.Members.PLAY_ORDER + " ASC"
        )

        cursor?.use {
            while (it.moveToNext()) {
                val audioId = it.getLong(
                    it.getColumnIndexOrThrow(
                        @Suppress("DEPRECATION") MediaStore.Audio.Playlists.Members.AUDIO_ID
                    )
                )
                // Create a MediaItem and add it to the list
                val song = songList.find { it1 ->
                    it1.mediaId.toLong() == audioId
                }
                if (song != null) {
                    songs.add(song)
                }
            }
        }

        cursor?.close()
        return songs
    }

    suspend fun updateLibraryWithInCoroutine(libraryViewModel: LibraryViewModel, context: Context) {
        val pairObject = getAllSongs(context)
        withContext(Dispatchers.Main) {
            libraryViewModel.mediaItemList.value = pairObject.songList
            libraryViewModel.albumItemList.value = pairObject.albumList
            libraryViewModel.artistItemList.value = pairObject.artistList
            libraryViewModel.albumArtistItemList.value = pairObject.albumArtistList
            libraryViewModel.genreItemList.value = pairObject.genreList
            libraryViewModel.dateItemList.value = pairObject.dateList
            libraryViewModel.playlistList.value = pairObject.playlistList
            libraryViewModel.folderStructure.value = pairObject.folderStructure
            libraryViewModel.shallowFolderStructure.value = pairObject.shallowFolder
            libraryViewModel.allFolderSet.value = pairObject.folders
        }
    }

}
