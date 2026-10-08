package io.github.audiz

/**
 * 🔥 КРОСС-ПЛАТФОРМЕННЫЕ КОНТРАКТЫ ДЛЯ ХРАНЕНИЯ МУЗЫКИ
 * Организованная структура: {basePath}/{Artist}/{Artist — Title.ext}
 */

/** Путь по умолчанию для сохранения музыки */
expect fun getDefaultMusicDir(): String

/** Сохранить трек в структурированную папку */
expect fun saveTrackFile(basePath: String, artist: String, fileName: String, bytes: ByteArray)

/** Сохранить файл напрямую в указанную папку: {targetDir}/{fileName} */
expect fun saveTrackToFolder(targetDir: String, fileName: String, bytes: ByteArray): String?

/** Скопировать существующий файл в указанную папку: {targetDir}/{destFileName} */
expect fun copyFileToFolder(sourceFilePath: String, targetDir: String, destFileName: String? = null): String?

/** Сохранить файл по прямому целевому пути: {destFilePath} */
expect fun saveFileToDirectPath(destFilePath: String, bytes: ByteArray): String?

/** Скопировать существующий файл по прямому целевому пути: {destFilePath} */
expect fun copyFileToDirectPath(sourceFilePath: String, destFilePath: String): String?

/** Проверить, существует ли трек на диске */
expect fun trackFileExists(basePath: String, artist: String, fileName: String): Boolean

/** Сохранить/загрузить настройку пути хранения музыки */
expect fun saveMusicStoragePath(path: String)
expect fun loadMusicStoragePath(): String?

/** Проверить существование файла по прямому пути */
expect fun localFileExists(filePath: String): Boolean

/** Разрешить локальный путь к файлу с fallback поиском / сопоставлением путей */
expect fun resolveLocalPath(path: String): String

/** Проверить, является ли путь существующей директорией */
expect fun isDirectory(path: String): Boolean

/** Прочитать содержимое текстового файла (например, плейлиста M3U) */
expect fun readTextFile(path: String): String?

/** Сканировать локальные аудиофайлы и сформировать список треков без сетевых запросов */
expect fun scanDownloadedTracks(basePath: String): List<io.github.audiz.models.FullTrackInfo>

/** Сохранить список треков плейлиста в локальный кеш на диске: {basePath}/playlists_cache/{playlistTitle}.json */
expect fun savePlaylistTracksCache(basePath: String, playlistTitle: String, tracks: List<io.github.audiz.models.FullTrackInfo>)

/** Загрузить список треков плейлиста из локального кеша на диске */
expect fun loadPlaylistTracksCache(basePath: String, playlistTitle: String): List<io.github.audiz.models.FullTrackInfo>

/** Сохранить список персональных плейлистов в кеш для бокового меню */
expect fun savePersonalPlaylistsCache(basePath: String, items: List<io.github.audiz.models.PersonalPlaylistItemData>)

/** Загрузить список персональных плейлистов из кеша */
expect fun loadPersonalPlaylistsCache(basePath: String): List<io.github.audiz.models.PersonalPlaylistItemData>

/** Сохранить список пользовательских плейлистов из Яндекса в кеш */
expect fun saveUserPlaylistsCache(basePath: String, items: List<io.github.audiz.models.PlaylistInfo>)

/** Загрузить список пользовательских плейлистов из Яндекса из кеша */
expect fun loadUserPlaylistsCache(basePath: String): List<io.github.audiz.models.PlaylistInfo>

/** Сохранить список локальных оффлайн-плейлистов на диск: {basePath}/playlists/local_playlists.json */
expect fun saveLocalPlaylists(basePath: String, playlists: List<io.github.audiz.models.LocalPlaylist>)

/** Загрузить список локальных оффлайн-плейлистов из {basePath}/playlists/local_playlists.json */
expect fun loadLocalPlaylists(basePath: String): List<io.github.audiz.models.LocalPlaylist>

/** Экспортировать локальный плейлист в формат M3U8: {basePath}/playlists/{title}.m3u8 */
expect fun exportPlaylistToM3u8(basePath: String, playlist: io.github.audiz.models.LocalPlaylist, tracks: List<io.github.audiz.models.FullTrackInfo>): String

/** Преобразовать список локальных путей к файлам в список объектов FullTrackInfo с проверкой наличия на диске */
expect fun getTracksFromLocalPaths(paths: List<String>): List<io.github.audiz.models.FullTrackInfo>

/** Удалить аудиофайл трека с диска (и пустую папку артиста, если файлов больше нет) */
expect fun deleteTrackFile(basePath: String, artist: String, trackTitle: String, localFilePath: String? = null): Boolean

/** Очистить весь кеш плейлистов: удаляет все файлы из {basePath}/playlists_cache/ */
expect fun clearPlaylistsCache(basePath: String): Int

/** Удалить все скачанные треки из папок HQ, LQ и корневой директории */
expect fun clearAllDownloadedMusic(basePath: String): Int

/** Дописать строку лога в файл отладки */
expect fun appendLogToFile(line: String)

/** Получить абсолютный путь к файлу логов */
expect fun getLogFilePath(): String

/** Очистка недопустимых символов в именах файлов с сохранением пробелов */
fun sanitizeKeepSpaces(input: String): String {
    val illegalChars = Regex("[\\\\/:*?\"<>|]")
    return input.replace(illegalChars, "_")
}

/** Получить листинг содержимого директории (подпапки и аудиофайлы) для встроенного проводника */
expect fun listFolderContents(folderPath: String, rootPath: String? = null): io.github.audiz.models.FolderListing

/** Загрузить байты обложки локального аудиофайла (встроенный тег APIC/PICTURE/covr или файл обложки в папке) */
expect fun loadLocalCoverBytes(pathOrUri: String): ByteArray?

/** Получить локальный URL/URI к обложке трека (для MPRIS/системных уведомлений) */
expect fun getLocalCoverArtUrl(pathOrUri: String): String?
