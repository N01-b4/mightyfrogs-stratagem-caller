# Changelog

## 1.3.0

- Use the supplied caller and receiver logos in Android and Windows app icons.
- Use Windows `SendInput` for key events and mark arrow keys with the extended-key flag.
- Increase default arrow hold time to 55 ms and use a 25 ms inter-key gap for cleaner input edges.
- Migrate older 40 ms and 90 ms defaults while preserving custom timing settings.
- Add input duration and settings migration tests.
- Package Android and Windows downloads together.

## 1.2.4

- Add a dark, angular tactical-style interface.
- Use arrow keys for stratagem directions and set a fast default input interval.
- Preserve the local-Wi-Fi phone/Windows receiver connection flow.
