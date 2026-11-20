package uk.co.cinematv.provider

import uk.co.cinematv.model.PlayableStream

data class ProviderReport(val name: String, val status: String, val detail: String, val count: Int = 0)
data class SourceScan(val streams: List<PlayableStream>, val reports: List<ProviderReport>)

/** All 40 names registered in DynamicProvidersFactory in the supplied Cinema HQ v9.1 APK. */
object ApkProviderCatalog {
    val names = listOf(
        "EasyNews","4KHDHub","HDHub","HiAnime","ReCloud","TryEmbed",
        "VidNest","VidZee","VidNestTV","VidSpark","VidRock","VidBolt",
        "CNO","YaStream","MovieBox","111477","RiveStream","ThugTV",
        "FrameXTV","VidQueen","WatchOut","PurStream","LMScript",
        "LookMovie","TrendiMovies","VidLink","NetPlayz","MeowTV",
        "FSOnline","Castle","VidVault","AniZone","ANV","123Anime",
        "Vuflix","VixSrc","Torrentio","TorrentClaw","VidSrc","2Embed"
    )

    fun canonical(name: String): String = when (name) {
        "VidLink web player","VidLink API" -> "VidLink"
        "2Embed web player" -> "2Embed"
        "RiveStream web player" -> "RiveStream"
        else -> name
    }

    fun unavailableReason(name: String) = when (name) {
        "EasyNews" -> "Requires a subscribed and authenticated account"
        "4KHDHub","HDHub","VidVault","TrendiMovies","FSOnline" ->
            "Site search and file-host extraction not implemented"
        "HiAnime","ReCloud","TryEmbed","VidNest","AniZone","ANV","123Anime" ->
            "Needs anime-ID matching and episode resolver"
        "VidRock","VidSpark","VidNestTV","VidQueen","VixSrc","LookMovie",
        "MeowTV","NetPlayz","Castle","111477" ->
            "Dynamic tokens, encryption or session-dependent resolver"
        "PurStream","LMScript","CNO","VidZee" ->
            "Multi-step API discovery not yet ported"
        "VidSrc" -> "No independently verified playable endpoint"
        else -> "No working adapter ported to Cinema TV"
    }
}
