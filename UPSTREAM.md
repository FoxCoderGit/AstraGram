# Telegram 13.2.2 source baseline

AstraGram 127 integrates Telegram for Android revision
[`21f7d109da6ab3506c9e9577f98c6a38771ab335`](https://github.com/DrKLO/Telegram/commit/21f7d109da6ab3506c9e9577f98c6a38771ab335).

The application code, protocol schema, JNI bridges, prebuilt archives and media modules are updated together. Existing AstraGram changes are retained on this baseline. Source dependencies are vendored at these upstream revisions:

| Component | Repository | Revision |
| --- | --- | --- |
| Wallet engine | [dkaraush/wallet-engine](https://github.com/dkaraush/wallet-engine) | `5c65e47cb8d68727c20d89382d0bc51292486b9a` |
| Calls | [TelegramMessenger/tgcalls](https://github.com/TelegramMessenger/tgcalls) | `545f5ae599f8282e1ae3f38aaff166d179d75703` |
| Prisma | [TelegramMessenger/libprisma](https://github.com/TelegramMessenger/libprisma) | `94640c63c20eacdf70e37e23198d9ab53c802ebd` |
| Boost regex | [boostorg/regex](https://github.com/boostorg/regex) | `4cbcd3078e6ae10d05124379623a1bf03fcb9350` |
| Widgets | [TelegramMessenger/twidget](https://github.com/TelegramMessenger/twidget) | `4769d72c5c5a9bace3c7bd8b6308cb79bc150bab` |
| Widget sandbox | [TelegramMessenger/widget-sandbox](https://github.com/TelegramMessenger/widget-sandbox) | `c498cefddefaba765282c9f3ab0b330f4bc990f6` |
| QuickJS | Widget sandbox's pinned submodule | `ca3594dae5fd0dd6d667be74a322e8e935a2d217` |
| FFmpeg | [FFmpeg/FFmpeg](https://github.com/FFmpeg/FFmpeg) | `8dca1586f468bfcbb78ae3ed618af1361d9f140a` |
| VPX | [webmproject/libvpx](https://github.com/webmproject/libvpx) | `6df3ec34557879fff673706f4a1d9fbd0f3a6f0e` |
| Lottie | [dkaraush/tlottie](https://github.com/dkaraush/tlottie) | `31f1b542f88e7b4be9a01e749920d857535fc715` |
| Media | [Arseny271/media](https://github.com/Arseny271/media) | `09b6c97c0d691c50cd4541e1573995d4066d6f5c` |

Large upstream media test fixtures are excluded from this application source distribution. To run those upstream tests, obtain `libraries/test_data/src/test/assets` from the pinned media repository revision. These files are not required to build or run AstraGram.

Third-party licences are retained in their component directories.
