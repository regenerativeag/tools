package regenerativeag.model

data class AddRoleConfig(
        val windowSize: Int,
        /** The minimum number of days the user must post on within the window in order to be granted the role */
        val minPostDays: Int,
)