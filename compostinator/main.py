import os
import sys
import yaml
from compostinator import Compostinator

def main():
    (discord_api_token, args) = parse_args(sys.argv)

    with open(args["config_path"], "r") as f:
        bot_config = yaml.safe_load(f)

    bot = Compostinator(args["dry_run"], bot_config, discord_api_token)
    bot.run()

def parse_args(argv):
    discord_api_token = os.environ.get("DISCORD_API_TOKEN")
    if discord_api_token == None or len(discord_api_token.strip()) == 0:
        raise Exception("DISCORD_API_TOKEN environment variable must be set")

    # TODO actually parse from command line
    args = {
        "config_path": "bot_config.yml",
        "dry_run": True
    }
    return (discord_api_token, args)

main()