# AstraGram feature catalogue

This page describes the user-facing AstraGram additions in version 12.10.6-astra.121. Standard Telegram features are not repeated here. Availability can depend on Android version, account type, chat permissions, network access and the policies of third-party services.

## Quick map

| Area | AstraGram additions |
| --- | --- |
| Plugins | Built-in optional modules and external `.plugin` packages |
| Media | Video-link downloads, background delivery, music, voice-message drawing and media tools |
| Messaging | Reaction notifications, interaction shortcuts, local chat tools and privacy controls |
| Organization | Attachment ordering, task queue, account layout, floating chats and app trigger |
| Services | Personal cloud, badges, release updates and diagnostics |

## Plugins

The **Plugins** screen is the central place for optional modules. Built-in modules are disabled until the user enables each one individually.

- **Yandex Music**: search for tracks, view "My likes", preview available tracks and send an audio file to a Telegram chat. Authorization uses the current user's own Yandex account or token and is stored locally on the device.
- **Voice-message drawing**: create a visual drawing-based silent voice message from the attachment flow.
- **yt-dlp video download**: download supported public video links, convert them to an Android-compatible MP4 when necessary and queue them for sending.
- **exteraGram supporter badges**: optionally display badges obtained from the public exteraGram profile service.
- **External plugin engine**: enable or disable the engine, install a `.plugin` file, reload installed plugins, search the plugin list, inspect plugin information and enable modules separately. The engine supports Python plugins and Java hooks.

External plugins are third-party code. Enable only plugins from sources you trust.

## Media and attachments

### Video links

- Detect supported video links in the compose field.
- Offer to download and send the result as a video instead of sending only the URL.
- Run download and conversion in the background task queue so the user can leave the chat.
- Remove the accepted link and its suggestion card from the compose field immediately after queuing the task.
- Prefer a mobile-friendly output quality. Portrait clips, including common TikTok 720 x 1280 video, are handled by their short side rather than being rejected solely because of height.
- Keep the original link available as the fallback when a provider blocks access, the video is private, authentication is required or the file cannot be prepared.

### Attachment workflow

- Drag to reorder supported attachment actions in the attachment menu.
- Keep the selected attachment layout as a local preference.
- Maintain an attachment-history helper and preview flow for supported media actions.
- Provide manual media tools and long-image export where supported by the selected content.

### Audio and round video tools

- Adjust playback gain for supported audio playback.
- Open round videos in a detached full-frame view without modifying the original Telegram document.
- Provide extended round-video playback information and seeking helpers where the media format permits it.
- Offer the optional drawing workflow for silent voice-message animations.

## Messaging and interaction

### Reactions and shortcuts

- Notify the account owner when another user reacts to one of their messages, including while AstraGram is backgrounded.
- Keep a dedicated reaction-event record so notification delivery is not inferred only from cached reaction state.
- Configure quick reactions per chat where the Telegram chat type supports reactions.
- Configure separate double-tap actions for incoming and outgoing messages: quick reaction, reply, edit, copy text or no action.

### Local conversation tools

- Choose the newer tabbed navigation or the legacy chat-list navigation.
- Show local previews for supported ephemeral media when the user enables that preference. This is a local viewing preference and does not alter Telegram server timers.
- Keep selected local history information after an explicit successful delete-for-everyone request, where the client has the required Telegram permission.
- Offer a detached full-frame view for a round video as a local copy.
- Provide a calculator hint for inserting a computed result into the message field.

### Privacy mode

Privacy mode is configured per account. Its independent switches cover read receipts, story-view receipts, online presence and typing signals, with an optional automatic-offline behaviour and a schedule. It only controls requests AstraGram is able to suppress; Telegram server behaviour and chat-specific rules still apply.

### Local simulations

The simulation tools create local-only chat states and responses for demonstration and testing. They do not send simulated messages to Telegram recipients. Simulation is kept separate from normal read-receipt and cloud operations.

## Organization and navigation

- **Background tasks**: one screen for active and completed supported media jobs, including cancellation and foreground-service status when Android requires it.
- **Floating chats**: manage supported floating chat bubbles locally on the device.
- **Account ordering**: arrange the order of Telegram accounts in the client interface.
- **Application trigger**: assign a locally installed Android application to the AstraGram trigger control and launch it from the configured action.
- **Custom chat preferences**: retain AstraGram interface preferences without changing remote Telegram messages.
- **Diagnostics**: generate a technical diagnostic report containing application, device and connection state while excluding account credentials.

## Profile and information tools

- Registration-date lookup flow for supported Telegram profiles.
- Sticker-owner information when the source metadata is available.
- Profile music presentation for supported media information.
- Local contact, status and block-management helpers where a Telegram account has the needed permissions.

## Personal cloud

The AstraGram cloud workflow uses a private Telegram channel owned by the current account as a personal media destination.

- Create or verify the private channel configuration.
- Upload eligible local media according to the selected schedule and days.
- Track queued uploads, retries and completion state locally.
- Browse and preview uploaded cloud media.
- Export supported cloud media to device storage.

The cloud is tied to the owner's account and requires the Android media permissions selected by the user.

## Badges

- **AstraGram creator badge**: a server-verified label for project creators.
- **AstraGram supporter badge**: a server-verified label for accounts that support the project.
- **Badge explanation sheet**: tap a displayed badge to read its meaning.
- **exteraGram supporter badge**: an optional, separately controlled badge sourced from exteraGram's public profile API.

Badges are presentation metadata. They are not Telegram's official verification marks.

## Updates and reliability

- Check for updates in the background.
- Compare release `versionCode` values correctly.
- Show release notes before installation.
- Download an APK inside AstraGram with retry and supported resume behaviour.
- Verify the APK SHA-256 digest and signing certificate before installation.
- Preserve application data by installing an accepted update over the existing package through Android's normal package installer.
- Support skipped releases and mandatory updates when the published release policy requires them.
- Keep a background connection service available for accounts that need persistent delivery when Google push delivery is unavailable.

## Security boundaries and limitations

- AstraGram does not bypass Telegram access controls, private-channel permissions or provider restrictions. A feature is available only where the relevant service permits it.
- A third-party video provider may refuse a download because a post is private, region-restricted, login-gated or otherwise unavailable.
- Yandex Music availability depends on the user account, service region and Yandex terms.
- External plugins can access AstraGram integration points; review them before enabling.
- Do not place server passwords, private signing keys, administrator tokens or private account-session data in the APK, source tree, issue reports or plugins.

## Not part of the public feature set

Internal test fixtures, build diagnostics, retired experiments and maintainer deployment tools are intentionally not presented as AstraGram user features. The project does not modify Telegram's standard single-message translation feature; it remains the upstream Telegram implementation.
