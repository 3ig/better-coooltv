package dev.bettercoool.tv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory profiles + channels for the current session, so screens don't refetch on every visit. */
class Catalog(private val api: CooolApi, private val session: SessionStore) {

    data class Snapshot(val profiles: List<Profile>, val profile: Profile?, val channels: List<Channel>)

    @Volatile
    var snapshot: Snapshot? = null
        private set

    private val _revision = MutableStateFlow(0)
    /** Bumps when the server says profiles/channels changed; screens reload on change. */
    val revision: StateFlow<Int> = _revision

    suspend fun load(force: Boolean = false): Snapshot {
        snapshot?.takeIf { !force }?.let { return it }
        val did = session.serverDeviceId ?: throw IllegalStateException("Not paired")
        val profiles = api.profiles(did)
        // Same rule as the original: saved profile if still present, else the only one.
        // A saved protected profile was unlocked with its PIN before, so it is reused as-is.
        val profile = profiles.firstOrNull { it.uuid == session.currentProfile }
            ?: profiles.singleOrNull()?.takeIf { !it.isLocked }
            ?: profiles.filterNot { it.isLocked }.singleOrNull()
        val channels = if (profile != null) api.channels(did, profile.uuid) else emptyList()
        return Snapshot(profiles, profile, channels).also { snapshot = it }
    }

    /** Caller is responsible for the PIN check on protected profiles. */
    suspend fun selectProfile(profile: Profile): Snapshot {
        session.currentProfile = profile.uuid
        snapshot = null
        return load()
    }

    fun invalidate() {
        snapshot = null
        _revision.value++
    }
}
