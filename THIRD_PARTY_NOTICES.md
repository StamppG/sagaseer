# Third-Party Notices

## RuneLite Time Tracking plugin (farming tables)

Source:
https://github.com/runelite/runelite, `net.runelite.client.plugins.timetracking` (farming and hunter packages), released as `net.runelite:client:1.13.2`

The plugin's `FarmingRegions.java` lists the farming areas (map regions, a tile test for five shared ones) and the patch varbits to read, as in
RuneLite's Time Tracking plugin, and the Fossil Island bird house varps and map regions.
The tables are generated from the released RuneLite 1.13.2 classes by SagaSeer's own server-side scripts; no RuneLite source code is
copied. Licensed BSD-2-Clause:

BSD 2-Clause License

Copyright (c) 2018, NotFoxtrot <https://github.com/NotFoxtrot> (timetracking/farming/FarmingWorld.java)
Copyright (c) 2018, 2019 Abex (timetracking/farming/FarmingWorld.java, FarmingRegion.java, PatchImplementation.java)
Copyright (c) 2018, Daniel Teo <https://github.com/takuyakanbr> (timetracking/hunter/BirdHouseSpace.java)
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## POH Clue Teleports (own-house entry rule)

Source:
https://github.com/ggaidelevicius/runelite-poh-clue-teleports, `HouseContext.java` (commit f2e740b5c8de873113eea0802df4860ec9bdc3b1, as listed on the RuneLite Plugin Hub)

The plugin's `HouseCapture.java` adapts its rule for telling the player's own house from a friend's: a visit counts only after the
player's own way in (the menu options it trusts and the ones that clear it), with the house loading within 30 ticks, and the house
map regions it checks. The code is rewritten for SagaSeer; the rule and its values come from POH Clue Teleports. Licensed BSD-2-Clause:

BSD 2-Clause License

Copyright (c) 2026, POH Clue Teleports contributors
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## reimagined-world-map (house template area)

Source:
https://github.com/Maiz19/reimagined-world-map, `RouteHouse` / `HouseTracker` (commit 01b4731cf34e0dee7bc5dfaf13883599ee3cd32b, as listed on the RuneLite Plugin Hub)

The plugin's `HouseCapture.java` uses the same map area for the game's house room templates (x 1856-2047, y 7040-7119) to tell a
house room chunk from anything else. No code is copied. Licensed BSD-2-Clause:

BSD 2-Clause License

Copyright (c) 2026, maiz
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Dude Where's My Stuff (bolt pouch types, menagerie pet bits)

Source:
https://github.com/Thource/dude-wheres-my-stuff, `BoltPouch.java` and `playerownedhouse/Menagerie.java` (commit 2f636ab8f75400219b6e35dd0412b42f510e528a, as listed on the RuneLite Plugin Hub)

The plugin's `StorageLocations.java` uses its list of which bolt each bolt pouch type number means (`BOLT_TYPES`), written with
RuneLite's gameval item names; a live check agreed. `HouseCapture.java` reads the menagerie's pet bits its way: three varps (PRAYER20,
MENAGERIE_CONTENTS2 and 3) whose set bits index the game's enum 985 of pet items. Its costume room and Tool Leprechaun readings were compared with
SagaSeer's, but no other code or table is taken. Licensed BSD-2-Clause:

BSD 2-Clause License

Copyright 2022 Thource
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## RuneLite Clue Scroll plugin (STASH units)

Source:
https://github.com/runelite/runelite, `net.runelite.client.plugins.cluescrolls` (`clues/emote/STASHUnit.java`, `clues/EmoteClue.java`), released as `net.runelite:client:1.13.3`

`StashUnits.java` lists the object ids of the game's 119 STASH units in the order of RuneLite's `STASHUnit` and checks them through the game's
own script (`ScriptID.WATSON_STASH_UNIT_CHECK`) the way the Clue Scroll plugin does. SagaSeer's server-side table of what each unit holds
(with "one of these" for alternatives) is generated from the released 1.13.3 classes' emote clue item requirements. No
source file is copied. Licensed BSD-2-Clause:

BSD 2-Clause License

Copyright (c) 2018, Lotto <https://github.com/devLotto> (cluescrolls/clues/EmoteClue.java, cluescrolls/clues/item/*)
Copyright (c) 2018 Abex (cluescrolls/clues/emote/STASHUnit.java)
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## RuneLite Discord plugin (death area names)

Source:
https://github.com/runelite/runelite, `net.runelite.client.plugins.discord` (`DiscordGameEventType`), released as `net.runelite:client:1.13.4`

The plugin's `DeathAreas.java` names the area a map region is in (a city, dungeon, boss lair, minigame, raid or region), used only to name where
the player died, and only the name is sent. The table is generated from the released RuneLite 1.13.4 class by SagaSeer's own server-side scripts; no
RuneLite source code is copied. Licensed BSD-2-Clause:

BSD 2-Clause License

Copyright (c) 2018, Tomas Slusny <slusnucky@gmail.com>
Copyright (c) 2018, PandahRS <https://github.com/PandahRS>
Copyright (c) 2020, Brooklyn <https://github.com/Broooklyn>
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Emote Clue Items (STASH noticeboard)

Source:
https://github.com/larsvansoest/emote-clue-items (commit 2ab1220cbccba512c2f6744b217c266524e70be9, as listed on the RuneLite Plugin Hub)

`StashBoard.java` reads the noticeboard in Watson's house the way this plugin found it can be read: a list per clue tier, each row a text
child and its check marks, two marks meaning filled. SagaSeer's server-side board wording for the units whose line differs from their name
comes from this plugin's `StashUnit`. No source file is copied. Licensed BSD-2-Clause:

BSD 2-Clause License

Copyright (c) 2021, Lars van Soest
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
