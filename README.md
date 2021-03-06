<img alt="Mantaro" src="https://i.imgur.com/b00buRW.png"/>

**Complete and configurable music, currency and games multipurpose Discord bot**

# Using the Official Mantaro Bot

**Just one [click](https://add.mantaro.site) and you can add Mantaro to your own server and enjoy its full feature set!**

You can see more information about the bot itself by reading the summary on [here](https://github.com/Mantaro/MantaroBot/blob/master/FEATURES.md). This is the file we use to publish our bots on bot lists, too. You're welcome to use it as a guide for your own.

Our [webpage](https://mantaro.site) is hosted in [Github Pages](https://github.com/Mantaro/mantaro.github.io). If you want to help us make a webpage, you can poke us.

# Building your own Mantaro

## ⚠ **Read before attempting**
The owners of Mantaro do not recommend compiling Mantaro as it is not documented, and most builds here will be extremely unstable and (probably) untested, probably including unfinished features. There's no *stable* branch, all the features are immediately added to upstream.\
You will however sometimes see a legacy branch, a branch we create before publishing larger update containing working code, it is highly recommended basing your building process from the legacy branch, as the master branch will very likely contain broken and non-working code, at least in a case where a legacy branch exist.

**We will not provide any support whatsoever in selfhosting or building the bot by yourself.**
The reason for this is that not only can the builds in here be highly unstable (probably they are!), but also there are very few people who could actually help with questions regarding this, most of which are busy and not available to answer said questions.

## Building the Bot

### Prerequisite:

You will need the following to utilize all of Mantaro's features (items marked with a star are optional):
* RethinkDB
* JDK
* Redis
* Wolke's Weeb API (For most of the action commands)*

**We will not provide any support whatsoever in obtaining any of the above.**

<sub>Note: The bot does not necessarily need these keys to function, but some functionality might be limited by the lack of them (ex. without Wolke's API keys, you can't use the action commands, and they won't register). Due to the closed nature of that API, we encourage you to submit a patch that would allow custom images to be used on self-hosted instances if you'd like (ex. by pushing your own -local- API server), but keep them in line with the rest of the code.</sub> 

### Editing Code:
Mantaro isn't a modular bot (sadly), but removing features is fairly easy. You can just remove the respective command or the Module file on the commands directory and everything -should- still work. The exception are some Modules that are required by other Modules.
Make sure you pay close attention to the [License](https://github.com/Mantaro/MantaroBot/blob/master/LICENSE) as you will be required to disclose your source as well as state any changes made.

### Steps for building:
<sub>Please do note that you will not receive any help whatsoever while trying to make your own Mantaro build.</sub>

1.  Make sure you have the prerequisites installed and running.
2.  Make sure your Java version is 15 or later.
2.  Clone this repository (you can also fork this repo and clone your fork). 
3.  Open a Terminal in the folder where you cloned this on.
4.  Run `gradlew shadowJar`
5.  Grab the jar from `build/libs`
6.  Install `rethinkdb` and `redis`
7.  Create the `mantaro` database with the following tables: `mantaro, players, marriages, playerstats, users, guilds, keys, commands, seasonalplayers`
8.  Run it and prepare yourself to start filling in some config values (open the jar on the command line using java -jar name.jar and wait for it to crash, then it'll generate the config.json file for you to fill). You don't need to fill all values, though. The token and the user id are necessary to start up, you wanna set the owner IDs aswell.
9.  In config.json, set the value needApi to false. (Or clone and run [the API](https://github.com/Kodehawa/mantaro-api))

### Extra runtime options:
This are java arguments that Mantaro parses outside of config.json and that can be dynamically adjusted using a script or similar:
*   `-Dmantaro.node-number=x` - Specify the number of this node (important if you use more than once instance)
*   `-Dmantaro.shard-count=x` - Specify the amount of shards Mantaro will be starting up. (for all nodes, see below for subsets)
*   `-Dmantaro.from-shard=x` - Start Shard Manager from shard X. (inclusive)
*   `-Dmantaro.to-shard=y`- Start Shard Manager up to shard Y. (exclusive)
*   `-Dmantaro.verbose` - Log all the things.
*   `-Dmantaro.verbose_shard_logs` - Show all shard status changes.
*   `-Dmantaro.debug` - Start Mantaro with two shards and some extra logging, regardless of the settings above.
*   `-Dmantaro.debug_logs` - Use debug logs.
*   `-Dmantaro.trace_logs` - Spam your logs.
*   `-Dmantaro.log_db_access` - Log all db access. (spams your logs aswell)
*   `-Dmantaro.disable-non-allocating-buffer` - Disable LP's non-allocating buffer.

The above options can also be used as environment variables by removing `-D`, and replacing `.` with `_`, for example, `-Dmantaro.node-number=x` becomes environment variable `MANTARO_NODE_NUMBER=x`

# Tools and Contributors

## Tools Mantaro uses
*   [JDA by DV8FromTheWorld and MinnDevelopment](https://github.com/DV8FromTheWorld/JDA)
*   [Lavaplayer by sedmelluq](https://github.com/sedmelluq/lavaplayer)
*   [Lavalink by Frederikam](https://github.com/Frederikam/Lavalink)
*   [RethinkDB by the RethinkDB team](http://rethinkdb.com)
*   [Redis by the redis team.](https://redis.io)
*   [imageboard-api by Kodehawa](https://github.com/Kodehawa/imageboard-api)
*   [Crowdin](https://translate.mantaro.site/) 
*   And a lot more!

## Important Contributors
Many thanks to

* [@natanbc](https://github.com/natanbc) - A lot of backend improvements and developing. Active.
* [@haxiz](https://github.com/Haxiz) - Responsible for maintaining user-facing documentation.
* [@mrlar](https://github.com/mrlar) - Wrote most of the user-facing documentation (no longer maintaining it). Responsible for maintaining/running the Support-Server.
* [@adriantodt](https://github.com/adriantodt) - Backend development on the early stages of the bot. Was crucial to the development and thanks to him this bot is what it is now. Inactive.

And a lot more people. Check the *[Contributors](https://github.com/Mantaro/MantaroBot/graphs/contributors)* tab!

Want to contribute? Join our [server](https://support.mantaro.site) and ask in the support channel for what we need help with (you may need to wait with receiving an answer.).\
Alternatively send us a Pull Request with what you see fit/think we need. However, this process may end in a rejected PR more easily.

Thanks to Crowdin for providing our project with a free OSS license to continue our localization efforts.

# Legal Stuff

## Using our code
Give credit where credit is due. If you wish to use our code in a project, **please** credit us, and take your time to read our full license. We don't mind you using Mantaro code, **as it is** open-source for a reason, as long as you don't blatantly copy it or refrain from crediting us.

## License

Copyright (C) 2016-2020 **David Rubio Escares** / **Kodehawa**

>This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License
>as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. 
>                                                   
>This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
>without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
>                                                   
>See the GNU General Public License for more details. 
>You should have received a copy of the GNU General Public License along with this program. If not, see http://www.gnu.org/licenses/

[The full license can be found here.](https://github.com/Kodehawa/MantaroBot/blob/master/LICENSE)
