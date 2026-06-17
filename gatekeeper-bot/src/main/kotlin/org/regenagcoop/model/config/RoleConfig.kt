package org.regenagcoop.model.config

import org.regenagcoop.discord.model.RoleId

data class RoleConfig(
    val roleId: RoleId?,
    val paths: List<Path>,
)