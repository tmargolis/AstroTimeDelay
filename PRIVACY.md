# Privacy

*Delayed Vision - Cosmically Inspired Video Mirrors* is an artwork that uses a camera to reflect viewers back to themselves across time. That premise depends on trust: a person standing before the mirror must feel confident that their image exists only within the piece, not beyond it. This document describes the privacy commitments that make that trust warranted.

---

## What the app captures

The app uses the device's front-facing camera to capture live video while a viewer stands before the mirror. Frames from that video are stored temporarily so they can be displayed after the mode-specific delay. No audio is ever captured or processed.

## What the app does not do

- **No network access.** The app has no internet permission and makes no outbound connections. Captured frames never leave the device.
- **No cloud storage.** Nothing is uploaded to any server, cloud service, or third-party platform.
- **No analytics or telemetry.** The app contains no analytics SDKs, crash reporters, ad networks, or usage trackers of any kind.
- **No persistent identity.** The app does not create user accounts, assign device identifiers, or link any data to any individual.

## Where frames are stored

Frames are written to the app's private external storage directory (`getExternalFilesDir()`), a location that:

- Is scoped to this app and not visible to other apps on the device
- Does not trigger Android's media scanner, so frames do not appear in the device's photo gallery or file manager
- Is automatically deleted by Android when the app is uninstalled

## How long frames are kept

Frame retention is determined by each mode's delay window:

| Mode | Retention policy |
|------|-----------------|
| Moon | Frames older than 1.3 seconds are evicted immediately as new frames arrive |
| Sun | Frames older than 8 minutes 20 seconds are deleted from disk as new frames arrive |
| Saturn | Frames older than 79 minutes are deleted from disk as new frames arrive |
| Proxima Centauri | Candidate frames from each 4-hour window are deleted at window end; only the single highest-motion winner is kept |

For Moon, Sun, and Saturn, the rolling buffer means that at any moment the device holds only as much video as the delay requires — not a growing archive. For Proxima Centauri, which stores one representative frame per four-hour window, the total accumulation grows very slowly (a few megabytes per day) and consists of single still images rather than continuous video.

## Installation context

### Gallery and museum modes (Moon, Sun, Saturn)

These three modes are designed for institutional exhibition. The device is secured to a wall or plinth and operated by the presenting institution. Viewers interact with it in a public space and are aware they are before a camera-based artwork; the app's behavior is fully disclosed as part of the installation.

Institutions wishing to clear all stored frames between exhibition periods can do so by uninstalling and reinstalling the app (which removes all data written to `getExternalFilesDir()`), or by using the in-app clear function, which deletes all session files and resets the buffer.

### Collector's Edition (Proxima Centauri)

The Proxima Centauri mode is a separate work intended for **permanent installation in a private home**. It is not offered for gallery or museum exhibition. Unlike the institutional modes — which are experienced by anonymous members of the public for brief durations — the Proxima mirror accumulates a record of the collector's domestic life over years.

Because of this, ownership carries meaningful privacy implications that go beyond a gallery visit. Before a collector receives the work, the artist conducts a thorough conversation about:

- What it means to have a camera continuously active in a living space
- How frames are stored (locally, privately, never transmitted — see above)
- The fact that the piece will hold images of the household spanning years
- The collector's rights and responsibilities regarding that accumulated record
- What happens to the work and its stored data if it changes hands or is decommissioned

The collector formally agrees to these terms before installation. The piece is not transferable without a corresponding conversation with the artist. This process is not a legal formality — it is part of the artwork itself, an acknowledgment that owning a mirror that remembers is an act that deserves deliberate, informed consent.

## Summary

All image data captured by this app:

- Stays on the device
- Is stored in an app-private location inaccessible to other apps
- Is automatically deleted as the delay window advances
- Is never transmitted, shared, or retained beyond its purpose within the artwork

---

*Copyright © 2025 Todd Margolis. All rights reserved. See [LICENSE](LICENSE) for terms.*
