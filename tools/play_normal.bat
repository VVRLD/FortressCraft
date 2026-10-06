@echo off
rem Like play.bat, but Minecraft opens a world with normal terrain, caves and mobs
rem ("FortCraft Normal", created the first time) instead of the flat test world.
rem To play one of your own worlds: copy its folder into fabric\run\saves, then run
rem   set FORTCRAFT_WORLD=<folder name>
rem   tools\play.bat
setlocal
set FORTCRAFT_WORLD=normal
call "%~dp0play.bat" %*
