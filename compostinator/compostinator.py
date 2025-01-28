import asyncio
import collections
import discord
import time

from model.message import Message

class Compostinator:
    def __init__(self, dry_run, config, discord_api_token):
        if len(config["channel_config_by_channel_id"]) == 0:
            raise Exception("At least one channel config must be present in 'channel_config_by_channel_id'")
        self._dry_run = dry_run
        self._config = config
        self._discord_api_token = discord_api_token
        self._loaded = False
        self._discord_message_queue = collections.deque()
        self._min_sleep_seconds = self._calc_min_sleep_seconds()

        self._messages_by_channel_id = {
            channel_id: collections.deque()
            for (channel_id, channel_config) in config["channel_config_by_channel_id"].items()
        }

        # TODO implement disappearing messages for threads
        self._message_by_thread_id = None 

    def run(self):
        print(f"Starting bot with dry_run={self._dry_run}")
        print(f"min sleep seconds: {self._min_sleep_seconds}")
        print(self._config)
        self._setup_discord()
        self._discord_client.run(self._discord_api_token)

    async def _on_ready(self):
        self._compostinator_user_id = self._discord_client.user.id
        for channel_id in self._config["channel_config_by_channel_id"]:
            discord_messages_in_channel = await self._fetch_discord_messages_in_channel(channel_id)
            messages_in_channel = [self._convert_discord_message_to_message(discord_message) for discord_message in discord_messages_in_channel if discord_message.author.id != self._compostinator_user_id]
            self._messages_by_channel_id[channel_id].extend(messages_in_channel)
            enable_message_posted = any(discord_message.author.id == self._compostinator_user_id for discord_message in discord_messages_in_channel)
            if not enable_message_posted:
                await self._post_enable_message(channel_id)
        self._loaded = True
        self._process_queue()
        self._schedule_next_delete()
        return True
        

    async def _on_message(self, message):
        print(f"received message {message.id} in channel {message.channel.id} ({message.channel.name})")
        self._discord_message_queue.append(message)
        if self._loaded:
            self._process_queue()
        return True

    def _process_queue(self):
        if not self._loaded:
            raise Exception("should only process message queue after loaded")

        while len(self._discord_message_queue) > 0:
            discord_message = self._discord_message_queue.popleft()
            message = self._convert_discord_message_to_message(discord_message)
            if message is None:
                continue
            if message.thread_id != None:
                raise Exception("threads not implemented yet")
            else:
                self._messages_by_channel_id[message.channel_id].append(message)

    def _schedule_next_delete(self):
        async def wait_then_delete():
            await asyncio.sleep(self._min_sleep_seconds)
            await self._do_delete()

        asyncio.create_task(wait_then_delete())

    async def _do_delete(self):
        now = time.time()

        for (channel_id, messages) in self._messages_by_channel_id.items():
            deleted = 0
            while len(messages) > 0 and messages[0].delete_timestamp <= now:
                message = messages.popleft()
                if not self._dry_run:
                    if message.thread_id is not None:
                        raise Exception("deletion not handled yet in threads")
                    await self._discord_client.http.delete_message(message.channel_id, message.id)
                    print(f"Deleted {message.id} from {message.channel_id}")
                deleted += 1
            if deleted > 0:
                if self._dry_run:
                    print(f"DRY RUN: would have deleted {deleted} messages from {channel_id}")
                else:
                    print(f"Deleted {deleted} messages from {channel_id}")
    
        self._schedule_next_delete()

    def _setup_discord(self):
        intents = discord.Intents.default()
        self._discord_client = discord.Client(intents=intents)

        @self._discord_client.event
        async def on_ready():
            await self._on_ready()

        @self._discord_client.event
        async def on_message(message):
            await self._on_message(message)

    def _convert_discord_message_to_message(self, discord_message):
        if discord_message.author.id == self._compostinator_user_id:
            return None

        if discord_message.channel.type == discord.ChannelType.text:
            channel_id = discord_message.channel.id
        else:
            return None
            # TODO implement for threads
            # TODO make sure works for private threads too
            # if message.channel.type == discord.ChannelType.public_thread \
            #         or message.channel.type == discord.ChannelType.private_thread:
            #     print(f"received message in thread... parent_channel: {message.channel.parent.id} ({message.channel.parent.name})")
        
        if channel_id not in self._config["channel_config_by_channel_id"]:
            return None

        delete_offset_seconds = self._get_delete_offset_seconds(channel_id)
        
        return Message(
            id=discord_message.id,
            channel_id=channel_id,
            thread_id=None,
            delete_timestamp=discord_message.created_at.timestamp() + delete_offset_seconds
        )

    def _get_delete_offset_seconds(self, channel_id):
        channel_config = self._config["channel_config_by_channel_id"][channel_id]
        delay_unit = channel_config["delay_unit"]
        delay_count = channel_config["delay_count"]

        if delay_unit == "SECONDS":
            multiplier = 1
        elif delay_unit == "MINUTES":
            multiplier = 60
        elif delay_unit == "HOURS":
            multiplier = 60 * 60
        elif delay_unit == "DAYS":
            multiplier = 60 * 60 * 24
        else:
            raise Exception("Invalid delay_unit for the config of channel_id={channel_id}. delay_unit was {delay_unit}")
        
        return multiplier * delay_count

    async def _fetch_discord_messages_in_channel(self, channel_id):
        channel = self._discord_client.get_channel(channel_id)
        discord_messages = []
        last_timestamp = None
        async for discord_message in channel.history(limit=None):
            discord_messages.append(discord_message)
            timestamp = discord_message.created_at.timestamp()
            if last_timestamp != None:
                current_message_is_older = timestamp <= last_timestamp
                if not current_message_is_older:
                    raise Exception("loaded messages out of order")
            last_timestamp = timestamp
        print(f"fetched {len(discord_messages)} messages from {channel_id} ({channel.name})")
        return list(reversed(discord_messages))

    async def _post_enable_message(self, channel_id):
        enable_config = self._config["enable_config"]
        channel_config = self._config["channel_config_by_channel_id"][channel_id]
        
        delay_unit = channel_config["delay_unit"]
        delay_count = channel_config["delay_count"]
        unit_config = enable_config["time_period_config"]["unit_config"][delay_unit]

        time_period_template = enable_config["time_period_config"]["template"]
        time_period_string = time_period_template \
            .replace("COUNT", str(delay_count)) \
            .replace("UNIT", unit_config["singular"] if delay_count == 1 else unit_config["plural"])
        
        message = enable_config["template"].replace("TIME_PERIOD", time_period_string)
        channel = self._discord_client.get_channel(channel_id)
        if self._dry_run:
            print(f"DRY RUN: would have posted \"{message}\" in {channel_id} ({channel.name})")
        else:
            await channel.send(message)

    def _calc_min_sleep_seconds(self):
        min_sleep_time_seconds = None
        for channel_id in self._config["channel_config_by_channel_id"].keys():
            delete_offset_seconds = self._get_delete_offset_seconds(channel_id)
            if min_sleep_time_seconds is None or delete_offset_seconds < min_sleep_time_seconds:
                min_sleep_time_seconds = delete_offset_seconds
        return min_sleep_time_seconds