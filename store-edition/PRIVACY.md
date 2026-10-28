# Music Box (store edition) — Privacy Policy

Version 2.18.12. Last reviewed 27 October 2026.

## The short version

The developer collects nothing about you. There is no account, no sign-up, no analytics, no advertising and no crash reporting, and no server belonging to the developer that the app talks to. What the app stores, it stores on your phone. Where it contacts another service, that is listed below and happens because you asked for it.

## Who is responsible

wander wildwood, the author of this version of the app. Contact: email@wanderthe.dev

Music Box is a fork of CalmMusic by David Ray Wilson (GPL-3.0), rebuilt on Mudita's public MMD library.

Because no personal data is transmitted to or held by the developer, there is no data controller relationship for the developer to administer. Where the app processes personal data, that processing happens on your own device, under your control.

## What the app stores, and where

On the phone, in the app's own private storage: the library index, playlists, the queue, a list of the last week's downloads, the names you give songs, albums and artists, the songs you heart, and settings. These never leave the phone except as described under backups below.

**A music server's address, user name and password.** If you add a Navidrome or other Subsonic server, its address and user name are kept in the app's settings file, and the password is sealed with an AES-GCM key generated in the phone's hardware-backed keystore (a key that cannot be exported) before it is written. If the key is lost, as after a restore onto another phone, the password cannot be read back and the app asks again. Usually the password is not sent: each request carries a salted hash of it (Subsonic's token sign-in). A server that does not accept that, such as Nextcloud Music, is sent the password itself, hex-encoded and not encrypted, with each request, as its protocol requires; Music Box says so on the server page. Use https for such a server. The developer runs no server and receives nothing.

**Downloaded songs.** Songs you download from your music server are saved as ordinary music files in Music/Music Box, on the memory card if there is one (on Android 9 and 10, in the app's own storage instead). They stay there if the app is uninstalled, and other apps on the phone can see them. Music files you put on the phone yourself are left alone.

**Backups.** The app allows Android's backup (`allowBackup="true"`). Android may therefore copy the library database and the preferences into your Google backup, or to a new phone when you transfer, as your phone's backup settings decide; the app's own files folder is included in a device-to-device transfer and, on Android 11 and older, in a full backup (songs saved in Music/Music Box are not in that folder, except on Android 9 and 10). The settings file, which holds the server password, is excluded from all of these. The developer holds no backup of any of it.

Uninstalling the app deletes everything in its private storage. There is no cloud copy and no copy held by the developer.

## Permissions, and what each is for

From `app/src/main/AndroidManifest.xml`, plus one that the libraries add when the app is built:

- `INTERNET`: for radio and your music server.
- `ACCESS_NETWORK_STATE`: added by a library, not listed in the manifest itself; the app uses it to know whether the phone is online.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: so music keeps playing with the screen off.
- `FOREGROUND_SERVICE_DATA_SYNC`: the kind of service the downloader is. It runs only while songs you asked for are arriving, with a notification and a Stop button.
- `WAKE_LOCK`: so playback and downloads are not interrupted.
- `POST_NOTIFICATIONS`: the player and download notifications.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: so the system does not stop playback.
- `READ_MEDIA_AUDIO`, and `READ_EXTERNAL_STORAGE` on Android 12 and older (which is what the Kompakt runs): asked for only when another app hands over a music file by its path rather than sharing it, so the app can play it. A file shared from another app comes with that app's permission to read that one file. You can refuse.

The store also lists `com.wanderwildwood.jimeikin.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` because it reads the APK. AndroidX declares it for its own internal receivers; it is signature-level and namespaced to this app, so nothing else can hold it and it grants no access to anything on the phone.

The manifest also names the FM radio apps that may be on the phone (`com.android.fmradio`, `com.mediatek.fmradio`, `com.caf.fmradio`), so the app can offer to open one; it reads nothing from them.

Cleartext http is permitted (network security config), because a music server on a home network is usually served over plain http. Anything fetched over http can be read by others on that network. In practice that is only a server you typed in yourself, or a radio station that streams over http.

Nothing a permission grants access to is sent anywhere, except as described in the next section.

## Lock-screen controls (optional)

Lock-screen controls are an accessibility service, off until you turn them on in Android's Accessibility settings. Only an accessibility service may draw above the Kompakt's lock screen, so that is what this has to be. It is limited in its configuration to one package, the system UI (`com.android.systemui`), and draws the app's play, pause, skip and stop buttons at the foot of the lock screen while something plays, standing aside for the PIN field and for anything another app shows over the lock screen. It reads no other app's content, and nothing it sees leaves the phone.

## Who else receives anything

Yes, where you ask for it. Each service is contacted only when that feature is used and operates under its own terms. No analytics, advertising or crash-reporting service is contacted at any point.

Hosts contacted:

- **radio.garden** (`radio.garden`): when you browse or play a radio station.
- **A station you add by its address**: when you add it (it is played once, silently, to check it) and whenever you play it. Its audio comes from the broadcaster's own stream server.
- **api.zippopotam.us**: when you type a US ZIP code or a Canadian postcode into the radio search, to turn it into a place. Only what you typed is sent.
- **Your own music server**: whichever Navidrome or other Subsonic server you configure.

On the phone: with Glance (`com.wanderwildwood.hitome`, the author's lock-screen app) installed, and Settings, "Show what is playing in Glance", turned on (it is off to begin with), the song playing is handed to Glance, and only to Glance, to show on the lock screen, where anyone holding the phone can read it.

In About, "feed the llamas" opens a donation page (square.link), wanderthe.dev opens the author's website and the source code link opens this app's page on GitHub, in your browser and only when you press them. The app does not fetch any of these pages and learns nothing about whether you went.

No data is sold, rented, or shared for advertising. There is no profiling, no personalization, and no automated decision-making about you.

## Legal basis (EU / EEA and UK)

Processing on the device is carried out for your own purposes and at your own direction. Where the app contacts an outside service, that request is made at your request and is necessary to provide the feature you asked for (Article 6(1)(b) GDPR); that operator is an independent controller for what it then does, under its own policy.

## Your rights

Under the GDPR and UK GDPR you have rights of access, rectification, erasure, restriction, portability and objection. Under the CCPA/CPRA and comparable North American laws you have rights to know, delete, correct and opt out of sale or sharing.

The developer holds no data about you, so there is nothing to request from them, and nothing has ever been sold or shared. You can exercise every one of these rights directly: the data is on your phone. Clear it in the app or in Android's app settings, or uninstall to remove all of it at once (downloaded songs in Music/Music Box are ordinary files and stay until you delete them).

## Children

The app is suitable for all ages and does not knowingly collect anything from anyone, of any age, because the developer does not collect anything.

## Changes

If a later version changes what the app stores or which hosts it contacts, this policy is updated in the same release and the change is described in the release notes. The source is public, so any claim here can be checked against the code: <https://github.com/wanderwildwood/jimeikin>. GitHub reports its licence as GPL-3.0.
