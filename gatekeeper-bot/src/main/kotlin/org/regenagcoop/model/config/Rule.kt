package org.regenagcoop.model.config

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import org.regenagcoop.discord.model.MessageId
import org.regenagcoop.discord.model.RoleId

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
sealed class Rule {

    /** True if all the sub [rules] are true */
    @JsonTypeName("and")
    data class And(
        val rules: List<Rule>
    ): Rule()

    /** True if the user currently has the role [roleId] */
    @JsonTypeName("has_role")
    data class HasRole(
        val roleId: RoleId?
    ): Rule()

    /** True if the user joined before [Path.daysToConsider] days ago */
    @JsonTypeName("joined_before")
    class JoinedBefore: Rule()

    /** True if the user has no posts or reactions in the last [Path.daysToConsider] days */
    @JsonTypeName("no_posts_or_reactions")
    class NoPostsOrReactions: Rule()

    /** True if any of the sub [rules] are true */
    @JsonTypeName("or")
    data class Or(
        val rules: List<Rule>,
    ): Rule()

    /** True if the user had [roleId] in the last [Path.daysToConsider] days */
    @JsonTypeName("previously_had_role")
    data class PreviouslyHadRole(
        val roleId: RoleId?
    ): Rule()

    /** True if the user has posted on at least [atLeast] distinct UTC days in the last [Path.daysToConsider] days */
    @JsonTypeName("post_days")
    data class PostDays(
        val atLeast: UInt,
    ): Rule()

    /** True if the user has posted on at least [atLeast] distinct UTC *weeks* in the last [Path.daysToConsider] days */
    @JsonTypeName("post_weeks")
    data class PostWeeks(
        val atLeast: UInt,
    ): Rule()

    /** True if the server is currently receiving a reaction event from the user of the given [emoji] on the given [messageId] */
    @JsonTypeName("reacted")
    data class Reacted(
        val emoji: String,
        val messageId: MessageId,
    ): Rule()
}