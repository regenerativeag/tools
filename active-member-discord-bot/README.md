# Active Member Discord Bot

Grants roles to discord community members based on their activity.

Currently runs in memory, no DB is required.

## Using this discord bot
For now, this bot is only capable of managing roles for a single server.

If you would like to use this bot on your server, you can follow these instructions:

1. Register your own discord bot with discord (directions are elsewhere on the internet 🙂)
2. Add the bot to your server and give it access to the appropriate rooms. Also give the bot access to manage roles.
3. Copy your bot's access token
4. Create an environment variable named `DISCORD_API_TOKEN` with the value of your bot's access token
5. Execute the shell command: `./gradlew active-member-discord-bot:run --args="bot-config.yml"`.
   - replace `bot-config.yml` with the path to your server's config
   - Alternatively, you can build the project (`./gradlew active-member-discord-bot:build`), and run the exported JAR (`java -jar active-member-discord-bot/build/libs/active-member-discord-bot-0.1-all.jar active-member-discord-bot/bot-config.yml`).

## Configuring the bot

Here is the config used for the Regenerative Ag discord server: [bot-config.yml](bot-config.yml)

You can copy this config and update it with values relevant to your server.

Currently, the rules used to configure this bot are:
- Grant role X if the user posts on Y unique days in a period of Z days
- Remove role X if the user hasn't posted on at least Y unique days in a period of Z days
    - _Currently, removal only occurs when the process first starts. There's an unfinished TODO to run the removal-checking code periodically._

Role configurations are provided in an ordered list. Active roles with the lowest priority should be in the beginning of the list (eg Guest), and roles with the highest priority should be in the end of the list. 
