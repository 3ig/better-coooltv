package dev.bettercoool.tv.data

/** In-memory profiles + channels for the current session, so screens don't refetch on every visit. */
class Catalog(private val api: CooolApi, private val session: SessionStore) {

    data class Snapshot(val profiles: List<Profile>, val profile: Profile?, val channels: List<Channel>)

    var snapshot: Snapshot? = null
        private set

    suspend fun load(force: Boolean = false): Snapshot {
        snapshot?.takeIf { !force }?.let { return it }
        val did = session.serverDeviceId ?: throw IllegalStateException("Not paired")
        val profiles = api.profiles(did)
        // Same rule as the original: saved profile if still present, else the only one.
        // We also auto-pick when exactly one profile is unlocked, since PIN entry isn't built yet.
        val profile = profiles.firstOrNull { it.uuid == session.currentProfile }
            ?: profiles.singleOrNull()
            ?: profiles.filterNot { it.isLocked }.singleOrNull()
        val channels = if (profile != null) api.channels(did, profile.uuid) else emptyList()
        return Snapshot(profiles, profile, channels).also { snapshot = it }
    }

    suspend fun selectProfile(profile: Profile): Snapshot {
        session.currentProfile = profile.uuid
        snapshot = null
        return load()
    }
}
