import sys
import yaml
from compostinator import Compostinator

def main():
    args = parse_args(sys.argv)
    with open(args["config_path"], "r") as f:
        bot_config = yaml.safe_load(f) # TODO what is proper syntax?

    bot = Compostinator(bot_config)
    bot.run()

def parse_args(argv):
    # TODO implement
    return {
        "config_path": "bot_config.yml"
    }

main()