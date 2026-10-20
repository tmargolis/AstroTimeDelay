# Privacy

*Delayed Vision - Cosmically Inspired Video Mirrors* is an artwork that uses a camera to reflect viewers back to themselves across time. That premise depends on trust: a person standing before the mirror must feel confident about where their image goes and who can see it. This document describes exactly what the app captures, what it sends over the network, and how long anything is kept.

---

## What the app captures

The app uses the device's front-facing camera to capture live video while a viewer stands before the mirror. Frames from that video are stored temporarily so they can be displayed after the mode-specific delay. No audio is ever captured or processed.

## What the app does not do

- **No analytics or telemetry.** The app contains no analytics SDKs, crash reporters, ad networks, or usage trackers of any kind.
- **No persistent identity.** The app does not create user accounts, assign device identifiers, or link any data to any individual.
- **No network activity in the gallery modes.** Moon, Sun, and Saturn make no outbound connections. Their frames never leave the device.
- **No uploads unless motion alerts are deliberately enabled.** Motion alerts are off by default and can only be turned on in a build configured for a specific installation (see below).

## Network activity

The app holds the Android internet permission. Only the Proxima Centauri mode uses it, for the two features described here.

### Headline ticker

Proxima Centauri shows news headlines from the date its delayed light left — 4.24 years ago. Once a day, and when the app starts, it requests that date's "Current events" page from Wikipedia (`en.wikipedia.org`). The request names only the historical date. It contains no images and no information about the viewer or household. As with any web request, Wikipedia's servers see the device's IP address. The most recent headlines are cached on the device so the ticker keeps working without a connection.

### Motion alerts (optional)

Motion alerts let the owner of a Proxima installation see when someone is in front of the mirror without waiting years for the delayed image. They are **disabled unless the app was built with an upload address and secret key** for that specific installation. When enabled:

- The app samples one frame per second and compares it with the previous one. Any frame with a motion score of 0.05 or more (roughly 5% of the image changing noticeably) is uploaded as a JPEG still image.
- Uploads go over HTTPS to a Google Apps Script web app owned by the person who set up alerts. The script checks the secret key and saves the image to a designated folder in that person's Google Drive.
- The first frame of each motion event also triggers a push notification through **ntfy.sh**, a third-party notification service. The notification contains the time, the motion score, and a link to the image in Google Drive. **The image itself is not sent to ntfy.sh.** Further notifications are held back until the scene has been still for 30 seconds.
- The Drive link opens only for Google accounts that have access to that Drive folder. Messages on ntfy.sh can be read by anyone who knows the notification topic name, which is a long random string kept private.

## Where frames are stored

Frames are written to the app's private external storage directory (`getExternalFilesDir()`), a location that:

- Is scoped to this app and not visible to other apps on the device
- Does not trigger Android's media scanner, so frames do not appear in the device's photo gallery or file manager
- Is automatically deleted by Android when the app is uninstalled

When motion alerts are enabled, copies of the uploaded frames are also stored in the configured Google Drive folder, governed by that Google account's settings.

## How long frames are kept

Frame retention on the device is determined by each mode's delay window:

| Mode | Retention policy |
|------|-----------------|
| Moon | Frames older than 1.3 seconds are evicted immediately as new frames arrive |
| Sun | Frames older than 8 minutes 20 seconds are deleted from disk as new frames arrive |
| Saturn | Frames older than 79 minutes are deleted from disk as new frames arrive |
| Proxima Centauri | Candidate frames from each 4-hour window are deleted at window end; only the single highest-graded winner is kept |

For Moon, Sun, and Saturn, the rolling buffer means that at any moment the device holds only as much video as the delay requires — not a growing archive. For Proxima Centauri, which stores one representative frame per four-hour window, the total accumulation grows very slowly (a few megabytes per day) and consists of single still images rather than continuous video.

**Uploaded frames are not covered by these rules.** Images saved to Google Drive by motion alerts stay there until the Drive account's owner deletes them; the app never deletes them. Notifications on ntfy.sh are held by that service only briefly so they can be delivered.

## Installation context

### Gallery and museum modes (Moon, Sun, Saturn)

These three modes are designed for institutional exhibition. The device is secured to a wall or plinth and operated by the presenting institution. Viewers interact with it in a public space and are aware they are before a camera-based artwork; the app's behavior is fully disclosed as part of the installation. These modes never transmit images.

Institutions wishing to clear all stored frames between exhibition periods can do so by uninstalling and reinstalling the app (which removes all data written to `getExternalFilesDir()`), or by using the in-app clear function, which deletes all session files and resets the buffer.

### Collector's Edition (Proxima Centauri)

The Proxima Centauri mode is a separate work intended for **permanent installation in a private home**. It is not offered for gallery or museum exhibition. Unlike the institutional modes — which are experienced by anonymous members of the public for brief durations — the Proxima mirror accumulates a record of the collector's domestic life over years.

Because of this, ownership carries meaningful privacy implications that go beyond a gallery visit. Before a collector receives the work, the artist conducts a thorough conversation about:

- What it means to have a camera continuously active in a living space
- How frames are stored on the device, and that the piece connects to the internet daily to fetch historical headlines
- Whether motion alerts will be enabled, and if so whose Google Drive receives the images and who receives the notifications
- The fact that the piece will hold images of the household spanning years
- The collector's rights and responsibilities regarding that accumulated record, including any copies in Google Drive
- What happens to the work and its stored data if it changes hands or is decommissioned

The collector formally agrees to these terms before installation. Motion alerts are enabled only with that agreement. The piece is not transferable without a corresponding conversation with the artist. This process is not a legal formality — it is part of the artwork itself, an acknowledgment that owning a mirror that remembers is an act that deserves deliberate, informed consent.

## Summary

- **Moon, Sun, and Saturn:** images stay on the device, in an app-private location, and are deleted as the delay window advances. Nothing is transmitted.
- **Proxima Centauri:** images stay on the device by default. The app connects to Wikipedia once a day for historical headlines, sending no images or personal data.
- **Proxima Centauri with motion alerts enabled:** frames showing motion are uploaded to a designated Google Drive folder and kept there until deleted, and ntfy.sh delivers a notification linking to them. This happens only in an installation built and agreed to for that purpose.
- **Everywhere:** no analytics, no tracking, no accounts, and no audio.

---

*Copyright © 2025 Todd Margolis. All rights reserved. See [LICENSE](LICENSE.md) for terms.*
