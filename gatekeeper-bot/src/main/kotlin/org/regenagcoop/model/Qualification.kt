package org.regenagcoop.model

import org.regenagcoop.model.config.Path
import org.regenagcoop.model.config.RoleConfig

/** What [RoleConfig] the user(s) qualified for, and by what [Path] */
data class Qualification(
    val roleConfig: RoleConfig,
    val path: Path,
)