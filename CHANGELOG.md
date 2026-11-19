# Changelog

## 13.2.2-astra.128

- Fix a crash when opening Devices: the session backup action now uses the current Telegram action bar.
- Add “В начало” / “Go to beginning” to the chat, group and channel menu. It resolves the oldest accessible message, handles deleted initial messages and migrated group history, and respects the current topic. Secret chats use their locally stored history.
- Allow cancelling the history lookup and discard responses after cancellation or closing the chat.

## 13.2.2-astra.127

- Upgrade the Telegram Android base to 13.2.2, including its protocol schema, native libraries and media components.
- Move “Скрытие данных собеседника” into the private chat's three-dot menu. The action toggles masking in the current chat and becomes “Показать данные собеседника” while enabled.
- Fix a RecyclerView crash when enabling or disabling safe screenshot mode: rendered message copies now retain their stable identifiers, including after rebinding.
- Remove personalised Premium reply backgrounds, patterns and colours while safe screenshot mode is enabled.
- Keep cloud uploads in Telegram's scheduled queue independently of ghost-mode settings, and prevent background accounts from announcing online status.
- Send an offline status for the previous account when switching accounts.

The Android package and signing identity are preserved for installation over earlier AstraGram builds without clearing application data.
