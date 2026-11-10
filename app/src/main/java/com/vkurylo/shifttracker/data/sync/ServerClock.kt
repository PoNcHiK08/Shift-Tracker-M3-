package com.vkurylo.shifttracker.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/** The server's time, to tell a phone clock that runs ahead from other reasons the cloud refuses an upload. */
fun interface ServerClock {
    /** Server time in UTC millis, or null if it can't be had right now. */
    suspend fun nowMillis(): Long?
}

/** The Date header of Firestore's own host: one small request, no Firestore read. Only used after a refusal. */
class FirestoreHostClock @Inject constructor() : ServerClock {
    override suspend fun nowMillis(): Long? = withContext(Dispatchers.IO) {
        try {
            val connection = URL("https://firestore.googleapis.com/").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.responseCode
                connection.date.takeIf { it > 0 }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
