# Third-party notices

The root MIT licence covers FortCraft's original Java, C++, scripts, protocol,
and documentation in this source preview. It does **not** relicense Valve's SDK,
Minecraft, Team Fortress 2, or the dependencies downloaded during a build.

`tf2mod/sdk-changes.patch` modifies Valve's Source SDK 2013. The SDK and its
modifications are governed by Valve's Source 1 SDK licence. Its required licence
and third-party notices are included in `third_party/source-sdk-2013/`.
Recipients obtain the unmodified SDK separately from
<https://github.com/ValveSoftware/source-sdk-2013> and apply our patch to it.
The SDK licence has non-commercial/free-distribution conditions; the MIT licence
on FortCraft's original code does not override them.

Minecraft and Team Fortress 2 game files are **not** included. Players must
obtain those games themselves. Gradle/Fabric Loom downloads the Minecraft
development dependencies during setup; no Minecraft JAR is in this archive.
The Gradle wrapper JAR is a build tool, not a game file.

FortCraft is an unofficial fan project. It is not affiliated with or endorsed
by Valve, Mojang, or Microsoft. This preview is single-player/offline only.

`prebuilt/fortcraft_flat.bsp` is FortCraft's own empty test map, compiled from
`tf2mod/maps/fortcraft_flat.vmf` with Valve's map compiler so Linux players (who have no
Windows map compiler) can use it. It contains only FortCraft's geometry and references to
standard tool materials, no Valve assets.
