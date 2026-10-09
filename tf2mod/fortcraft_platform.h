// FortCraft: everything that differs between Windows and Linux on the TF2 side, in one place.
// The rest of tf2mod/ calls these and never touches Win32, POSIX or SDL directly.
//
// Windows: named page-file mapping, GetTickCount64, Win32 windows and cursor.
// Linux:   /dev/shm file + mmap, CLOCK_MONOTONIC, TF2's own SDL2 window (looked up at run time).
#pragma once

#include <stddef.h>
#include <stdint.h>

// Map Minecraft's shared memory. NULL while Minecraft hasn't created it yet; call again later.
void *FortCraftPlat_OpenLink( size_t bytes );

// Milliseconds for heartbeats, on the same clock Minecraft uses (see FortLink.tickCount).
uint64_t FortCraftPlat_NowMs();

// Microseconds on the clock Java's System.nanoTime uses (QueryPerformanceCounter on Windows,
// CLOCK_MONOTONIC on Linux): stamps overlay frames so Minecraft can pace them.
uint64_t FortCraftPlat_NowUs();

unsigned long FortCraftPlat_ProcessId();

// ---- Window, focus and cursor (client only) ------------------------------------------------
// pLog receives one finished line (for TF2's console, Msg).
typedef void ( *FortCraftPlatLog )( const char *pszLine );

// Hide every window TF2 owns. Linux: -fortcraft_offscreen moves it off-screen instead.
void FortCraftPlat_HideOwnWindows( FortCraftPlatLog pLog );

// Before a video-mode change: remember who has focus (Minecraft). After it: give focus back.
void FortCraftPlat_RememberFocus();
void FortCraftPlat_RestoreFocus( FortCraftPlatLog pLog );

// Line TF2's hidden window up with Minecraft's visible one when both have this client size, so
// TF2's UI reads the same cursor position Minecraft shows (Windows: measured 320,192 offset).
// Linux: logs TF2's window position and size when they change, nothing else yet.
void FortCraftPlat_AlignToHost( unsigned long hostPid, int width, int height, FortCraftPlatLog pLog );

// The desktop mouse pointer.
bool FortCraftPlat_GetCursor( int *pX, int *pY );
void FortCraftPlat_SetCursor( int x, int y );

// Minecraft let go of the mouse: make sure TF2 isn't holding it. bChanged: first frame of it.
void FortCraftPlat_ReleaseCursor( unsigned long hostPid, bool bChanged, FortCraftPlatLog pLog );
