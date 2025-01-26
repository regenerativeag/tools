package org.regenagcoop.model.config

data class DirectMessageConfig(
    val message: String,
) {
    fun createDirectMessage(): String {
        return message
    }
}