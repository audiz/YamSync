package io.github.audiz.api

object ApiUrls {
    const val SEARCH_URL = "https://api.music.yandex.ru/search/instant/mixed"
    const val TRACKS_URL  = "https://api.music.yandex.ru/tracks"
    const val LANDING_LIKES_URL = "https://api.music.yandex.ru/landing-blocks/collection/playlist-with-likes"
    const val PLAYLIST_BY_UUID_URL = "https://api.music.yandex.ru/playlist" // + /{uuid}?resumeStream=false&richTracks=false
    const val HISTORY_URL = "https://api.music.yandex.ru/music-history"
    const val PERSONAL_PLAYLISTS_URL = "https://api.music.yandex.ru/landing-blocks/personal-playlists"
    const val USER_PLAYLISTS_BASE_URL = "https://api.music.yandex.ru/users"
}