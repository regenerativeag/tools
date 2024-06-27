package regenerativeag.model

data class KeepRoleConfig(
        val windowSize: Int,
        /** The minimum number of days the user must post on within the window in order to keep the role */
        val minPostDays: Int,
)