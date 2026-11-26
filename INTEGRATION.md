# Fossify Phone with Ringtone Goofer

This branch integrates [Ringtone Goofer](https://github.com/viktis1/ringtone-goofer) into Fossify Phone. Fossify Phone's GPL-3.0 license and source history are retained.

The Phone pager includes a Ringtones tab. Contact and call-history actions open ringtone and voice tools, while Fossify handles dialing and in-call UI. Incoming calls can queue ringtone generation after the call ends.

## Build

Clone with the pinned Commons submodule:

```sh
git clone --recurse-submodules -b ringtone-goofer-integration https://github.com/viktis1/Phone.git
cd Phone
./gradlew :app:assembleFossDebug :app:testFossDebugUnitTest
```

The [Commons fork](https://github.com/viktis1/commons) contains the compatibility changes needed by this app and is pinned to a specific commit. The app uses the existing Goofer Firebase registration and backend configuration; other deployments need their own configuration.

## Current limitations

Generation and uploaded recordings use the existing cloud services. Call history and ringtone job metadata remain local. The backend does not currently provide a history-list API, so full cloud history restoration is not implemented. Android contact and call providers remain in use.

The integrated build and unit tests passed, and an incoming call queued a generation job on a Samsung SM-S921B. End-to-end ringtone assignment still needs verification after server-side generation completes.
