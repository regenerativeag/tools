package org.regenagcoop.model.config

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import org.regenagcoop.discord.model.MessageId
import org.regenagcoop.discord.model.RoleId

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
sealed class Rule {

    @JsonTypeName("and")
    data class And(
        val rules: List<Rule>
    ): Rule()

    @JsonTypeName("has_role")
    data class HasRole(
        val roleId: RoleId
    ): Rule()

    @JsonTypeName("joined_before")
    class JoinedBefore: Rule()

    @JsonTypeName("no_posts_or_reactions")
    class NoPostsOrReactions: Rule()

    @JsonTypeName("previously_had_role")
    data class PreviouslyHadRole(
        val roleId: RoleId
    ): Rule()

    @JsonTypeName("post_days")
    data class PostDays(
        val atLeast: UInt,
    ): Rule()

    @JsonTypeName("post_weeks")
    data class PostWeeks(
        val atLeast: UInt,
    ): Rule()

    @JsonTypeName("reacted")
    data class Reacted(
        val emoji: String,
        val messageId: MessageId,
        val removeReaction: Boolean = true,
    ): Rule()
}