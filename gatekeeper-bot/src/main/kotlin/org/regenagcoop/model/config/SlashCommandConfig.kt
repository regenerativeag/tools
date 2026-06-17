package org.regenagcoop.model.config

data class SlashCommandConfig(
    /** Command users run as `/post` by default */
    val postCommand: String = "post",
    /** Command users run as `/edit` by default */
    val editCommand: String = "edit",
)
