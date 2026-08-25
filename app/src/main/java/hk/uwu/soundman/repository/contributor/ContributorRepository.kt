package hk.uwu.soundman.repository.contributor

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL

data class ContributorProfile(
    @SerializedName("name")
    val name: String,
    @SerializedName("description")
    val description: String,
    @SerializedName("link")
    val link: String? = null,
    @SerializedName("avatar")
    val avatar: String? = null,
)

sealed interface ContributorLoadState {
    data object Idle : ContributorLoadState
    data object Loading : ContributorLoadState
    data class Loaded(val contributors: List<ContributorProfile>) : ContributorLoadState
    data object Failed : ContributorLoadState
}

private data class ContributorResponse(
    @SerializedName("contributors")
    val contributors: List<ContributorProfile> = emptyList(),
)

object ContributorRepository {
    private const val CONTRIBUTORS_URL = "https://reareye.uwu.hk/soundman_contributors.json"

    private val requestLock = Mutex()
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    private val _state = MutableStateFlow<ContributorLoadState>(ContributorLoadState.Idle)
    val state: StateFlow<ContributorLoadState> = _state.asStateFlow()

    fun preload() {
        ensureLoaded(force = false)
    }

    fun ensureLoaded(force: Boolean) {
        val current = _state.value
        if (!force && (current is ContributorLoadState.Loading || current is ContributorLoadState.Loaded)) {
            return
        }

        requestScope.launch {
            requestLock.withLock {
                val latest = _state.value
                if (!force && (latest is ContributorLoadState.Loading || latest is ContributorLoadState.Loaded)) {
                    return@withLock
                }

                _state.value = ContributorLoadState.Loading
                val contributors = fetchContributors()
                _state.value = if (contributors != null) {
                    ContributorLoadState.Loaded(contributors)
                } else {
                    ContributorLoadState.Failed
                }
            }
        }
    }

    private fun fetchContributors(): List<ContributorProfile>? {
        return runCatching {
            val connection = URL(CONTRIBUTORS_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.requestMethod = "GET"

            connection.inputStream.use { stream ->
                val body = stream.bufferedReader().readText()
                val payload = gson.fromJson(body, ContributorResponse::class.java)
                payload.contributors.mapNotNull(::normalizeContributor)
            }
        }.onFailure {
            Log.d("Contributor", "fetch error", it)
        }.getOrNull()
    }

    private fun normalizeContributor(item: ContributorProfile): ContributorProfile? {
        val name = item.name.trim()
        if (name.isBlank()) return null

        return ContributorProfile(
            name = name,
            description = item.description.trim(),
            link = item.link?.trim().takeUnless { it.isNullOrBlank() },
            avatar = item.avatar?.trim().takeUnless { it.isNullOrBlank() },
        )
    }
}
