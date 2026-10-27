# Music Box (store edition)

A music player for the [Mudita Kompakt](https://mudita.com/products/mudita-kompakt): the music
on the phone and its card, a Navidrome or other Subsonic server of your own, and internet
radio, on the e-ink screen and in Mudita's MMD design.

This is the edition offered in Mudita's store. It is built from the same source as the
edition on GitHub (`./gradlew :app:assembleStoreRelease`, from `app/src/main` and
`app/src/store`), signed with the same key, under the same name and version.

## Local music

Settings → **Add a folder**. Supported audio files are indexed into songs, albums, artists and
playlists, and play offline. Artists are grouped by album artist where the files carry one.
Any `.m3u` or `.m3u8` file inside a chosen folder becomes a playlist, in file order.

A song's tags can be changed from its long-press menu → **Edit details**, and a whole album's
name and album artist from the pencil on its page; the change is written into the file, through
a copy that is checked before it replaces the original. Any song, album or artist can be given
a name of your own with **Edit name**, kept in the app and leaving the file as it is.

Hold a song and choose **Heart** to mark it; a **Hearted** list heads Playlists.

## A music server

Settings → **Music server**. Address, username, password, and it reads the whole library in one
go, beside the music on the card. Anything Subsonic answers works: Navidrome, Airsonic, Gonic.
A song held both on the server and on the card is shown once, as the copy on the card. A dotted
rule under a row means it needs the network.

Long press a song for **Download**, or use the download button on an album, an artist or a
playlist. Downloads go one at a time, in the order asked for, into **Music/Music Box** as
ordinary music files, and keep going with the screen off. **Downloads** lists what is waiting,
underway and finished in the last week.

## Radio

**Stations** on the internet, browsed by place or found by name, town or postcode, from
[radio.garden](https://radio.garden)'s directory. Hold a station to keep it in **Favorite
stations**. **FM** opens the phone's own tuner, if it has one.

## Also

One queue, with shuffle and repeat. Large-type Now Playing. Lock-screen controls (an optional
accessibility service, off until you turn it on). "Open with" for any audio file. What is
playing can be shown on [Glance](https://github.com/wanderwildwood/hitome)'s lock screen, if you
turn that on.

## What it sends

Your settings and library stay on the phone. The app goes online only for what you use: your
own music server, at the address you type in; radio.garden, to browse stations, and each
station's own stream while it plays; and api.zippopotam.us, only for a US or Canadian postcode
typed into the radio search. There are no ads, no analytics and no tracking. A music server's
password is sealed with a key in the phone's keystore. The full policy is in
[PRIVACY.md](PRIVACY.md); the same is said in the app, under **Privacy** and **Terms** in About.

## Credits and licence

Built on [CalmMusic](https://github.com/davidraywilson/CalmMusic) by David Ray Wilson. UI with
Mudita's MMD library (Apache 2.0), the Lato typeface (SIL OFL 1.1) and Material Symbols icons
(Apache 2.0). Tags with jaudiotagger (LGPL 2.1). AndroidX, Jetpack Compose, Media3, Room,
OkHttp, Kotlin and kotlinx.coroutines are Apache 2.0.

GPL-3.0. Source: <https://github.com/wanderwildwood/jimeikin>.
