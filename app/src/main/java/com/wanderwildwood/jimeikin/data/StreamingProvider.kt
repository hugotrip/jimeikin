package com.wanderwildwood.jimeikin.data

enum class StreamingProvider {
    YOUTUBE,
    ;

    companion object {
        fun fromStored(value: String?): StreamingProvider = YOUTUBE

        fun toStored(provider: StreamingProvider): String = "YOUTUBE"
    }
}