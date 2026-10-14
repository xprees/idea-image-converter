<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Image Tools Changelog

## [Unreleased]

## [2026.2.1] - 2026-10-14

### Changed

- Compatibility range is now derived from the supported IDE version (`platformVersion`): the plugin targets IDE builds 262 to 262.* (2026.2); 2026.1 is no longer supported

### Added

- Convert SVG to PNG, JPG, WebP or AVIF at its original size, ×2, ×3 or a custom scale / resolution; the custom size dialog keeps the aspect ratio by default and warns when it no longer matches
- Resize Image… also works on SVG files (writes a PNG)
- The resize dialog shows the original aspect ratio and warns when the lock is off and the ratio differs
- **Convert Image to…** in the Project view and editor tab context menus: PNG, JPG, WebP and AVIF, with quality presets (80 / 90 / 100), custom quality and lossless mode for WebP and AVIF
- **Resize Image…** by pixels (aspect ratio lock) or percentage
- Batch conversion of multiple selected images as one cancellable background task with a summary notification
- EXIF orientation applied to the pixels; EXIF and ICC metadata preserved
- Settings page: background colour for transparent images converted to JPG, default quality for resizing

[Unreleased]: https://github.com/xprees/idea-image-converter/compare/v2026.2.1...HEAD
[2026.2.1]: https://github.com/xprees/idea-image-converter/commits/v2026.2.1
