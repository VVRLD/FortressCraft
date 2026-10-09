// FortCraft: Windows and Linux versions of the few system calls the TF2 side needs.
// See fortcraft_platform.h. Compiled into both client and server.
#include "cbase.h"
#include "tier0/icommandline.h"
#include "fortcraft_platform.h"
#include "../protocol/fortcraft_protocol.h"

#include <limits.h>
#include <stdio.h>

#ifndef _WIN32
#include <dlfcn.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#endif

// memdbgon must be the last include file in a .cpp file!!!
#include "tier0/memdbgon.h"

namespace proto = fortcraft::proto;

#ifdef _WIN32
// ================================ Windows ====================================================
// The few Windows calls we need, declared here rather than pulling windows.h into game code.
extern "C" __declspec( dllimport ) void *__stdcall OpenFileMappingW( unsigned long access, int inherit, const wchar_t *name );
extern "C" __declspec( dllimport ) void *__stdcall MapViewOfFile( void *mapping, unsigned long access, unsigned long offHigh, unsigned long offLow, size_t bytes );
extern "C" __declspec( dllimport ) unsigned long long __stdcall GetTickCount64();
extern "C" __declspec( dllimport ) unsigned long __stdcall GetCurrentProcessId();
typedef int( __stdcall *FortCraftEnumProc )( void *hwnd, intptr_t param );
extern "C" __declspec( dllimport ) int __stdcall EnumWindows( FortCraftEnumProc proc, intptr_t param );
extern "C" __declspec( dllimport ) unsigned long __stdcall GetWindowThreadProcessId( void *hwnd, unsigned long *pid );
extern "C" __declspec( dllimport ) int __stdcall IsWindowVisible( void *hwnd );
extern "C" __declspec( dllimport ) int __stdcall ShowWindow( void *hwnd, int cmd );
extern "C" __declspec( dllimport ) void *__stdcall GetForegroundWindow();
struct FortCraftRect { long left, top, right, bottom; };
extern "C" __declspec( dllimport ) int __stdcall GetClipCursor( FortCraftRect *pRect );
extern "C" __declspec( dllimport ) int __stdcall ClipCursor( const FortCraftRect *pRect );
extern "C" __declspec( dllimport ) int __stdcall SetForegroundWindow( void *hwnd );
struct FortCraftPoint { long x, y; };
extern "C" __declspec( dllimport ) int __stdcall GetWindowRect( void *hwnd, FortCraftRect *pRect );
extern "C" __declspec( dllimport ) int __stdcall GetClientRect( void *hwnd, FortCraftRect *pRect );
extern "C" __declspec( dllimport ) int __stdcall ClientToScreen( void *hwnd, FortCraftPoint *pPoint );
extern "C" __declspec( dllimport ) int __stdcall SetWindowPos( void *hwnd, void *insertAfter, int x, int y, int width, int height, unsigned int flags );
#pragma push_macro("GetCursorPos")
#undef GetCursorPos
extern "C" __declspec( dllimport ) int __stdcall GetCursorPos( FortCraftPoint *pPoint );
static bool FortCraft_GetSystemCursor( FortCraftPoint *pPoint ) { return ::GetCursorPos( pPoint ) != 0; }
#pragma pop_macro("GetCursorPos")
#pragma push_macro("SetCursorPos")
#undef SetCursorPos
extern "C" __declspec( dllimport ) int __stdcall SetCursorPos( int x, int y );
static void FortCraft_SetSystemCursor( int x, int y ) { ::SetCursorPos( x, y ); }
#pragma pop_macro("SetCursorPos")

static const unsigned long FILE_MAP_ALL_ACCESS_ = 0xF001F;

void *FortCraftPlat_OpenLink( size_t bytes )
{
	void *hMapping = OpenFileMappingW( FILE_MAP_ALL_ACCESS_, 0, proto::kMappingName );
	if ( !hMapping )
		return NULL;  // Minecraft isn't running yet
	return MapViewOfFile( hMapping, FILE_MAP_ALL_ACCESS_, 0, 0, bytes );
}

uint64_t FortCraftPlat_NowMs() { return GetTickCount64(); }

extern "C" __declspec( dllimport ) int __stdcall QueryPerformanceCounter( long long *pCount );
extern "C" __declspec( dllimport ) int __stdcall QueryPerformanceFrequency( long long *pFrequency );
uint64_t FortCraftPlat_NowUs()
{
	static long long s_nFrequency;
	if ( !s_nFrequency )
		QueryPerformanceFrequency( &s_nFrequency );
	long long nCount = 0;
	QueryPerformanceCounter( &nCount );
	return s_nFrequency > 0 ? (uint64_t)( (double)nCount / (double)s_nFrequency * 1e6 ) : 0;
}

unsigned long FortCraftPlat_ProcessId() { return GetCurrentProcessId(); }

#ifdef CLIENT_DLL  // windows and cursor: the client only (keeps user32 out of server.dll)
// Hide TF2's window once, when launched with -fortcraft_hidden: Minecraft is the one you see.
static int __stdcall HideOurWindow( void *hwnd, intptr_t )
{
	unsigned long pid = 0;
	GetWindowThreadProcessId( hwnd, &pid );
	if ( pid == GetCurrentProcessId() && IsWindowVisible( hwnd ) )
		ShowWindow( hwnd, 0 /* SW_HIDE */ );
	return 1;
}

void FortCraftPlat_HideOwnWindows( FortCraftPlatLog )
{
	EnumWindows( HideOurWindow, 0 );
}

static void *s_hRestoreFocus;

void FortCraftPlat_RememberFocus()
{
	s_hRestoreFocus = GetForegroundWindow();  // Minecraft, normally
}

void FortCraftPlat_RestoreFocus( FortCraftPlatLog )
{
	// A mode change can also make TF2 the active window, which steals the keyboard and
	// locks the mouse; hand focus back to whoever had it (Minecraft).
	if ( s_hRestoreFocus )
		SetForegroundWindow( s_hRestoreFocus );
	s_hRestoreFocus = NULL;
}

struct FortCraftWindowPair
{
	unsigned long hostPid;
	int width, height;
	void *ours, *host;
};

static int __stdcall FindLinkedWindows( void *hwnd, intptr_t param )
{
	FortCraftWindowPair *pair = reinterpret_cast< FortCraftWindowPair * >( param );
	unsigned long pid = 0;
	GetWindowThreadProcessId( hwnd, &pid );
	if ( pid != pair->hostPid && pid != GetCurrentProcessId() )
		return 1;
	FortCraftRect client;
	if ( !GetClientRect( hwnd, &client ) || client.right - client.left != pair->width
		|| client.bottom - client.top != pair->height )
		return 1;
	if ( pid == pair->hostPid && IsWindowVisible( hwnd ) )
		pair->host = hwnd;
	else if ( pid == GetCurrentProcessId() )
		pair->ours = hwnd;
	return 1;
}

// VGUI reads the Windows cursor relative to TF2's hidden client area. Video-mode
// changes resized that client to Minecraft's 1920x1080 but left its old (320,191)
// desktop origin, exactly matching Alex's hover/drag offset. Align the origins,
// not the pointer, and only when both client areas really are the same size.
void FortCraftPlat_AlignToHost( unsigned long hostPid, int width, int height, FortCraftPlatLog pLog )
{
	FortCraftWindowPair pair = { hostPid, width, height, NULL, NULL };
	EnumWindows( FindLinkedWindows, reinterpret_cast< intptr_t >( &pair ) );
	if ( !pair.ours || !pair.host )
		return;
	FortCraftPoint ourOrigin = { 0, 0 }, hostOrigin = { 0, 0 };
	FortCraftRect ourWindow;
	if ( !ClientToScreen( pair.ours, &ourOrigin ) || !ClientToScreen( pair.host, &hostOrigin )
		|| !GetWindowRect( pair.ours, &ourWindow ) )
		return;
	if ( ourOrigin.x == hostOrigin.x && ourOrigin.y == hostOrigin.y )
		return;
	const int x = ourWindow.left + hostOrigin.x - ourOrigin.x;
	const int y = ourWindow.top + hostOrigin.y - ourOrigin.y;
	// NOSIZE | NOZORDER | NOACTIVATE | NOOWNERZORDER. Do not show or focus TF2.
	if ( SetWindowPos( pair.ours, NULL, x, y, 0, 0, 0x0215 ) )
	{
		FortCraftPoint aligned = { 0, 0 };
		ClientToScreen( pair.ours, &aligned );
		char szLine[ 256 ];
		V_snprintf( szLine, sizeof( szLine ), "FortCraft menu windows: TF2 client %dx%d origin %ld,%ld -> %ld,%ld; Minecraft origin %ld,%ld\n",
			width, height, ourOrigin.x, ourOrigin.y, aligned.x, aligned.y, hostOrigin.x, hostOrigin.y );
		pLog( szLine );
	}
}

bool FortCraftPlat_GetCursor( int *pX, int *pY )
{
	FortCraftPoint p;
	if ( !FortCraft_GetSystemCursor( &p ) )
		return false;
	*pX = (int)p.x;
	*pY = (int)p.y;
	return true;
}

void FortCraftPlat_SetCursor( int x, int y )
{
	FortCraft_SetSystemCursor( x, y );
}

// The cursor lock on Windows is one global rectangle (ClipCursor) that any program can set; if
// one is still in place, release it, and log who had focus so the real culprit shows up.
void FortCraftPlat_ReleaseCursor( unsigned long hostPid, bool bChanged, FortCraftPlatLog pLog )
{
	FortCraftRect clip;
	GetClipCursor( &clip );
	// Only a pin (a tiny rectangle, e.g. the 1-pixel one seen at Minecraft's centre) counts as a
	// lock. A fullscreen game is normally kept to its own monitor, and that must stay.
	bool bClipped = ( clip.right - clip.left ) < 200 || ( clip.bottom - clip.top ) < 200;

	unsigned long fgPid = 0;
	GetWindowThreadProcessId( GetForegroundWindow(), &fgPid );
	static bool s_bWasClipped;
	bool bLog = bChanged || ( bClipped && !s_bWasClipped );
	s_bWasClipped = bClipped;
	if ( bLog )
	{
		char szLine[ 256 ];
		V_snprintf( szLine, sizeof( szLine ), "FortCraft: Minecraft let go of the mouse; focus is with pid %lu (%s), cursor %s %ld,%ld-%ld,%ld\n",
			fgPid, fgPid == GetCurrentProcessId() ? "TF2" : fgPid == hostPid ? "Minecraft" : "other",
			bClipped ? "LOCKED to" : "free,", clip.left, clip.top, clip.right, clip.bottom );
		pLog( szLine );
	}
	if ( bClipped )
		ClipCursor( NULL );
}

#endif  // CLIENT_DLL

#else
// ================================ Linux ======================================================

void *FortCraftPlat_OpenLink( size_t bytes )
{
	// Minecraft creates this file (FortLink.createLinux); /dev/shm lives in RAM.
	int fd = open( proto::kMappingPathPosix, O_RDWR );
	if ( fd < 0 )
		return NULL;  // Minecraft isn't running yet
	struct stat st;
	if ( fstat( fd, &st ) != 0 || (uint64_t)st.st_size < (uint64_t)bytes )
	{
		close( fd );  // Minecraft is still growing it; try again later
		return NULL;
	}
	void *pView = mmap( NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0 );
	close( fd );  // the mapping stays valid without the descriptor
	return pView == MAP_FAILED ? NULL : pView;
}

uint64_t FortCraftPlat_NowMs()
{
	struct timespec ts;
	clock_gettime( CLOCK_MONOTONIC, &ts );
	return (uint64_t)ts.tv_sec * 1000ull + (uint64_t)ts.tv_nsec / 1000000ull;
}

uint64_t FortCraftPlat_NowUs()
{
	struct timespec ts;
	clock_gettime( CLOCK_MONOTONIC, &ts );
	return (uint64_t)ts.tv_sec * 1000000ull + (uint64_t)ts.tv_nsec / 1000ull;
}

unsigned long FortCraftPlat_ProcessId() { return (unsigned long)getpid(); }

#ifdef CLIENT_DLL  // windows and cursor: the client only

// TF2 on Linux makes its window with SDL2, already loaded in this process. Look the few
// functions up at run time so the build needs no SDL headers or libraries.
struct SDL_Window;
static const uint32_t kSdlWindowShown = 0x00000004, kSdlWindowHidden = 0x00000008, kSdlWindowInputFocus = 0x00000200;
static struct
{
	bool tried, ok;
	SDL_Window *( *GetWindowFromID )( uint32_t );
	uint32_t ( *GetWindowFlags )( SDL_Window * );
	void ( *HideWindow )( SDL_Window * );
	void ( *GetWindowPosition )( SDL_Window *, int *, int * );
	void ( *SetWindowPosition )( SDL_Window *, int, int );
	void ( *GetWindowSize )( SDL_Window *, int *, int * );
	uint32_t ( *GetGlobalMouseState )( int *, int * );
	int ( *WarpMouseGlobal )( int, int );
	SDL_Window *( *GetGrabbedWindow )();
	void ( *SetWindowGrab )( SDL_Window *, int );
	int ( *GetRelativeMouseMode )();
	int ( *SetRelativeMouseMode )( int );
	const char *( *GetCurrentVideoDriver )();
} s_Sdl;

template < typename T > static void SdlFind( void *hLib, T &fn, const char *pszName )
{
	fn = (T)( hLib ? dlsym( hLib, pszName ) : NULL );
	if ( !fn )
		fn = (T)dlsym( RTLD_DEFAULT, pszName );
	if ( !fn )
		s_Sdl.ok = false;
}

static bool Sdl( FortCraftPlatLog pLog )
{
	if ( s_Sdl.tried )
		return s_Sdl.ok;
	s_Sdl.tried = true;
	s_Sdl.ok = true;
	void *hLib = dlopen( "libSDL2-2.0.so.0", RTLD_LAZY | RTLD_NOLOAD );
	SdlFind( hLib, s_Sdl.GetWindowFromID, "SDL_GetWindowFromID" );
	SdlFind( hLib, s_Sdl.GetWindowFlags, "SDL_GetWindowFlags" );
	SdlFind( hLib, s_Sdl.HideWindow, "SDL_HideWindow" );
	SdlFind( hLib, s_Sdl.GetWindowPosition, "SDL_GetWindowPosition" );
	SdlFind( hLib, s_Sdl.SetWindowPosition, "SDL_SetWindowPosition" );
	SdlFind( hLib, s_Sdl.GetWindowSize, "SDL_GetWindowSize" );
	SdlFind( hLib, s_Sdl.GetGlobalMouseState, "SDL_GetGlobalMouseState" );
	SdlFind( hLib, s_Sdl.WarpMouseGlobal, "SDL_WarpMouseGlobal" );
	SdlFind( hLib, s_Sdl.GetGrabbedWindow, "SDL_GetGrabbedWindow" );
	SdlFind( hLib, s_Sdl.SetWindowGrab, "SDL_SetWindowGrab" );
	SdlFind( hLib, s_Sdl.GetRelativeMouseMode, "SDL_GetRelativeMouseMode" );
	SdlFind( hLib, s_Sdl.SetRelativeMouseMode, "SDL_SetRelativeMouseMode" );
	SdlFind( hLib, s_Sdl.GetCurrentVideoDriver, "SDL_GetCurrentVideoDriver" );
	char szLine[ 256 ];
	V_snprintf( szLine, sizeof( szLine ), "FortCraft linux: SDL2 %s (library %s, video driver %s)\n",
		s_Sdl.ok ? "found" : "NOT found: window hiding and cursor handling are off", hLib ? "loaded" : "not loaded by name",
		s_Sdl.ok && s_Sdl.GetCurrentVideoDriver() ? s_Sdl.GetCurrentVideoDriver() : "?" );
	if ( pLog )
		pLog( szLine );
	return s_Sdl.ok;
}

// SDL numbers windows from 1. TF2 has one or two (game window, maybe a splash).
template < typename F > static void ForEachSdlWindow( F fn )
{
	for ( uint32_t id = 1; id <= 32; ++id )
	{
		SDL_Window *pWindow = s_Sdl.GetWindowFromID( id );
		if ( pWindow )
			fn( id, pWindow );
	}
}

void FortCraftPlat_HideOwnWindows( FortCraftPlatLog pLog )
{
	if ( !Sdl( pLog ) )
		return;
	// Hiding (unmapping) is the Windows behaviour. Some Linux drivers stop presenting frames for
	// an unmapped window, which could stall TF2; -fortcraft_offscreen keeps it shown, far away.
	const bool bOffscreen = CommandLine()->FindParm( "-fortcraft_offscreen" ) != 0;
	ForEachSdlWindow( [ & ]( uint32_t id, SDL_Window *pWindow ) {
		const uint32_t flags = s_Sdl.GetWindowFlags( pWindow );
		char szLine[ 256 ];
		if ( bOffscreen )
		{
			int x = 0, y = 0;
			s_Sdl.GetWindowPosition( pWindow, &x, &y );
			if ( x <= -16000 )
				return;
			s_Sdl.SetWindowPosition( pWindow, -20000, -20000 );
			s_Sdl.GetWindowPosition( pWindow, &x, &y );
			V_snprintf( szLine, sizeof( szLine ), "FortCraft linux: TF2 window %u moved off-screen, now at %d,%d\n", id, x, y );
		}
		else
		{
			if ( ( flags & kSdlWindowHidden ) || !( flags & kSdlWindowShown ) )
				return;
			s_Sdl.HideWindow( pWindow );
			V_snprintf( szLine, sizeof( szLine ), "FortCraft linux: TF2 window %u hidden (flags %08x -> %08x)\n", id, flags, s_Sdl.GetWindowFlags( pWindow ) );
		}
		pLog( szLine );
	} );
}

void FortCraftPlat_RememberFocus()
{
	// SDL can't hand focus to another program's window; see RestoreFocus.
}

void FortCraftPlat_RestoreFocus( FortCraftPlatLog pLog )
{
	// Only report it: if TF2 kept the keyboard after a video-mode change, the tester will see
	// this line and Minecraft won't react to keys until they click its window.
	if ( !Sdl( pLog ) )
		return;
	ForEachSdlWindow( [ & ]( uint32_t id, SDL_Window *pWindow ) {
		if ( s_Sdl.GetWindowFlags( pWindow ) & kSdlWindowInputFocus )
		{
			char szLine[ 128 ];
			V_snprintf( szLine, sizeof( szLine ), "FortCraft linux: TF2 window %u has keyboard focus after a mode change\n", id );
			pLog( szLine );
		}
	} );
}

void FortCraftPlat_AlignToHost( unsigned long, int, int, FortCraftPlatLog pLog )
{
	// Not ported: Windows needed it because TF2's hidden window kept an old desktop origin. Log
	// where TF2's window is, so a menu-cursor offset (if any) can be measured on Linux first.
	if ( !Sdl( pLog ) )
		return;
	static int s_nLast[ 4 ] = { INT_MIN, INT_MIN, INT_MIN, INT_MIN };
	ForEachSdlWindow( [ & ]( uint32_t id, SDL_Window *pWindow ) {
		if ( id != 1 )
			return;
		int now[ 4 ] = { 0, 0, 0, 0 };
		s_Sdl.GetWindowPosition( pWindow, &now[ 0 ], &now[ 1 ] );
		s_Sdl.GetWindowSize( pWindow, &now[ 2 ], &now[ 3 ] );
		if ( V_memcmp( now, s_nLast, sizeof( now ) ) == 0 )
			return;
		V_memcpy( s_nLast, now, sizeof( now ) );
		char szLine[ 160 ];
		V_snprintf( szLine, sizeof( szLine ), "FortCraft linux: TF2 window at %d,%d size %dx%d\n", now[ 0 ], now[ 1 ], now[ 2 ], now[ 3 ] );
		pLog( szLine );
	} );
}

bool FortCraftPlat_GetCursor( int *pX, int *pY )
{
	if ( !Sdl( NULL ) )
		return false;
	s_Sdl.GetGlobalMouseState( pX, pY );
	return true;
}

void FortCraftPlat_SetCursor( int x, int y )
{
	if ( Sdl( NULL ) )
		s_Sdl.WarpMouseGlobal( x, y );  // unsupported on Wayland: does nothing there
}

// Linux has no global cursor clip; a program locks the mouse by grabbing it to one of its
// own windows or by relative mode. Undo either if TF2 holds it.
void FortCraftPlat_ReleaseCursor( unsigned long, bool bChanged, FortCraftPlatLog pLog )
{
	if ( !Sdl( pLog ) )
		return;
	SDL_Window *pGrabbed = s_Sdl.GetGrabbedWindow();
	const bool bRelative = s_Sdl.GetRelativeMouseMode() != 0;
	if ( bChanged || pGrabbed || bRelative )
	{
		static bool s_bWasLocked;
		const bool bLocked = pGrabbed || bRelative;
		if ( bChanged || bLocked != s_bWasLocked )
		{
			char szLine[ 160 ];
			V_snprintf( szLine, sizeof( szLine ), "FortCraft: Minecraft let go of the mouse; TF2 %s (grab %d, relative %d)\n",
				bLocked ? "HELD it, releasing" : "isn't holding it", pGrabbed ? 1 : 0, bRelative ? 1 : 0 );
			pLog( szLine );
		}
		s_bWasLocked = bLocked;
	}
	if ( pGrabbed )
		s_Sdl.SetWindowGrab( pGrabbed, 0 );
	if ( bRelative )
		s_Sdl.SetRelativeMouseMode( 0 );
}

#endif  // CLIENT_DLL

#endif  // _WIN32
