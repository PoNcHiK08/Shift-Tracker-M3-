package com.vkurylo.shifttracker.data.sync.firebase

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException

/** Why a cloud call failed, as far as the user can act on it. */
enum class CloudProblem {
    /** The phone is offline, or the cloud can't be reached right now. */
    OFFLINE,

    /** firestore.rules refused: the signed-in email isn't the allowed one, or the rules aren't published. */
    ACCESS_DENIED,

    /** Anything else. */
    OTHER,
}

fun Throwable.cloudProblem(): CloudProblem = when {
    this is FirebaseNetworkException -> CloudProblem.OFFLINE
    this is FirebaseFirestoreException -> when (code) {
        FirebaseFirestoreException.Code.UNAVAILABLE, FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> CloudProblem.OFFLINE
        FirebaseFirestoreException.Code.PERMISSION_DENIED, FirebaseFirestoreException.Code.UNAUTHENTICATED -> CloudProblem.ACCESS_DENIED
        else -> CloudProblem.OTHER
    }
    else -> CloudProblem.OTHER
}
