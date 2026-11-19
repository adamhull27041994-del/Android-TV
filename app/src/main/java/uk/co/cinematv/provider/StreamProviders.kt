package uk.co.cinematv.provider

import uk.co.cinematv.data.LibraryStore
import uk.co.cinematv.model.*

/** Independently pluggable sources. No Cinema HQ private endpoints are used. */
interface StreamProvider {
    val name: String
    suspend fun resolve(key: StreamKey): List<PlayableStream>
}

/** Returns HTTPS streams manually added by the user for content they are authorised to view. */
class PersonalStreamProvider(private val store: LibraryStore) : StreamProvider {
    override val name = "Personal sources"
    override suspend fun resolve(key: StreamKey) = store.streamsFor(key)
}

/** Register additional licensed streaming API adapters here. */
class ProviderRegistry(private val providers: List<StreamProvider>) {
    suspend fun resolve(key: StreamKey): List<PlayableStream> = providers.flatMap { provider ->
        runCatching { provider.resolve(key) }.getOrDefault(emptyList())
    }.distinctBy { it.url }
}
