package org.regenagcoop.discord.service

import org.regenagcoop.discord.mock.DiscordMocker

class PersistReactionServiceTest {
    private val discordMocker = DiscordMocker()

    // TODO test: locates "Yesterday" and "Today" messages correctly
    // case: Yesterday and Today
    // random order: add to yesterday, add to today

    // case: Yesterday, no Today
    // random order: add to yesterday, create today

    // case: no Yesterday, Today
    // random order: create yesterday, add to today

    // case: no Yesterday and no Today
    // random order: create yesterday, create today


    // TODO test: posting message out of bounds throws error
    // case: Today & Yesterday defined
    // post-day=yesterday-1=error
    // post-day=yesterday=ok
    // post-day=today=ok
    // post-day=tomorrow=ok
    // post-day=tomorrow+1=error

    // case: only Yesterday defined
    // post-day=yesterday-1=error
    // post-day=yesterday=ok
    // post-day=today=ok
    // post-day=tomorrow=ok
    // post-day=tomorrow+1=error

    // case: only Today defined
    // post-day=yesterday-1=error
    // post-day=yesterday=ok
    // post-day=today=ok
    // post-day=tomorrow=ok
    // post-day=tomorrow+1=error

    // case: no days defined
    // post-day=yesterday-1=error
    // post-day=yesterday=ok
    // post-day=today=ok
    // post-day=tomorrow=ok
    // post-day=tomorrow+1=error


    // TODO test: posting message for "tomorrow" does swap correctly
    // case: Today & Yesterday defined
    // post to tomorrow->create new today
    // random order: post to old today->add. post to old yesterday->error. post to new today->add.

    // case: only yesterday defined
    // post to tomorrow->create new today
    // random order: post to old today->create. post to old yesterday->error. post to new today->add.

    // case: only today defined
    // post to tomorrow->create new today
    // random order: post to old today->add. post to old yesterday->error. post to new today->add.

    // case: no days defined
    // post to tomorrow->create new today
    // random order: post to old today->create. post to old yesterday->error. post to new today->add.
}