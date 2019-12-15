[example-config]: https://github.com/MinnDevelopment/strumbot/blob/master/example-config.json

# Strumbot

A Twitch Stream Notification Bot. This will send notifications to a webhook in your Discord server when the subscribed streamer goes live or changes their game.

## Requirements

- JDK 1.8 or better
- Stable Internet

## Configurations

The configuration file must be called `config.json` and has to be in the working directory. An example configuration can be found in [`example-config.json`][example-config].
Anything marked with **(optional)** can be set to `null` to be disabled.

### Discord

This section of the configuration contains settings for the discord side of the bot such as role names and webhook URLs.

- `token` The discord bot token
- `stream_notifications` The webhook URL to send stream updates to
- `message_logs` **(optional)** The webhook URL for message logs (edits/deletes)
- `role_name` Configuration of `type`->`role` to change the default names of the update roles
- `enabled_events` Array of events to publish to the `stream_notifications` webhook

#### Events

- `live` When the streamer goes live
- `update` When the streamer changes the current game
- `vod` When the streamer goes offline (includes vod timestamps for game changes)

### Twitch

This configuration section contains required information to track the stream status.

- `client_id` The twitch application's client_id
- `client_secret` The twitch application's client_secret (currently unused)
- `user_login` The username of the tracked streamer

### Example

```json
{
  "discord": {
    "token": "NjUzMjM1MjY5MzM1NjQ2MjA4.*******.*******",
    "stream_notifications": "https://discordapp.com/api/webhooks/*******/******",
    "message_logs": null,
    "role_name": {
      "live": "live",
      "vod": "vod",
      "update": "update"
    },
    "enabled_events": ["live", "update", "vod"]
  },
  "twitch": {
    "client_id": "*******",
    "client_secret": "*******",
    "user_login": "Elajjaz"
  }
}
```

## Setup

Currently I only provide 2 setups. Either [docker](https://hub.docker.com) or through a script.
I'm open for pull requests that introduce more or better setups.

### Docker

1. Download the zip archive from the [latest release](https://github.com/MinnDevelopment/strumbot/releases/latest)
1. Unzip and open the resulting directory in a terminal of your choice
1. Change the configuration in `config.json`
1. Build the image with `docker build -t strumbot:latest .`
1. Create the container with `docker create --name strumbot --restart unless-stopped strumbot:latest`
1. Run the container with `docker start strumbot`

### Script

1. Download the zip archive from the [latest release](https://github.com/MinnDevelopment/strumbot/releases/latest)
1. Unzip and open the resulting directory in a terminal of your choice
1. Change the configuration in `config.json`
1. Run the script for your current platform:
    1. Windows: `run.bat`
    1. Linux: `run.sh`
