package com.mdreader.ui

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface OnlineSearchPhase<out T> {
    data object Idle : OnlineSearchPhase<Nothing>
    data class Searching(val query: String) : OnlineSearchPhase<Nothing>
    data class Loaded<T>(val query: String, val items: List<T>) : OnlineSearchPhase<T>
    data class Failed(val query: String, val message: String) : OnlineSearchPhase<Nothing>
}

data class OnlineSearchState<T>(
    val phase: OnlineSearchPhase<T> = OnlineSearchPhase.Idle,
    val requestId: Long = 0,
) {
    val query: String
        get() = when (val current = phase) {
            OnlineSearchPhase.Idle -> ""
            is OnlineSearchPhase.Searching -> current.query
            is OnlineSearchPhase.Loaded -> current.query
            is OnlineSearchPhase.Failed -> current.query
        }

    val items: List<T>
        get() = (phase as? OnlineSearchPhase.Loaded)?.items ?: emptyList()

    val isSearching: Boolean
        get() = phase is OnlineSearchPhase.Searching

    val errorMessage: String?
        get() = (phase as? OnlineSearchPhase.Failed)?.message

    fun startSearch(rawQuery: String): OnlineSearchState<T>? {
        val query = rawQuery.trim()
        if (query.isEmpty()) return null
        return copy(
            phase = OnlineSearchPhase.Searching(query),
            requestId = requestId + 1,
        )
    }

    fun startBrowse(): OnlineSearchState<T> = copy(
        phase = OnlineSearchPhase.Searching(""),
        requestId = requestId + 1,
    )

    fun onResults(id: Long, items: List<T>): OnlineSearchState<T> {
        if (id != requestId) return this
        return copy(phase = OnlineSearchPhase.Loaded(query, items))
    }

    fun onFailure(id: Long, message: String): OnlineSearchState<T> {
        if (id != requestId) return this
        return copy(phase = OnlineSearchPhase.Failed(query, message))
    }
}

data class OnlineOpenState<T>(
    val opening: T? = null,
    val error: String? = null,
    val failedItem: T? = null,
) {
    val isOpening: Boolean
        get() = opening != null

    fun begin(item: T): OnlineOpenState<T> = copy(
        opening = item,
        error = null,
        failedItem = null,
    )

    fun failed(item: T, message: String): OnlineOpenState<T> = copy(
        opening = null,
        error = message,
        failedItem = item,
    )

    fun succeeded(): OnlineOpenState<T> = copy(
        opening = null,
        error = null,
        failedItem = null,
    )
}

fun onlineSearchErrorMessage(error: Throwable): String = when (error) {
    is UnknownHostException -> "Check your connection and try again."
    is SocketTimeoutException -> "Free Web Novel took too long to answer."
    is IOException -> "Couldn't reach Free Web Novel."
    else -> error.message?.takeIf { it.isNotBlank() } ?: "Couldn't search Free Web Novel."
}

fun onlineOpenErrorMessage(error: Throwable, title: String): String =
    "Couldn't add \"$title\". ${onlineSearchErrorMessage(error)}"
