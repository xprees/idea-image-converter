# Image Tools

<!-- Plugin description -->
Convert and resize images straight from the Project view and editor tabs.

Right-click one or more images and choose <b>Convert Image to…</b> to get PNG, JPG, WebP or AVIF
(with quality presets, custom quality and lossless mode), or <b>Resize Image…</b> to scale by pixels or percentage.
EXIF orientation and colour profiles are preserved, transparent images are flattened onto a configurable
colour when converted to JPG, and originals are never touched: results are written next to the source.
<!-- Plugin description end -->

Works in every JetBrains IDE based on the IntelliJ Platform 2026.1 or newer (IntelliJ IDEA, WebStorm, Rider, PyCharm, …).

## Usage

Select one or more image files (`.png`, `.jpg`/`.jpeg`, `.webp`, `.avif`, `.bmp`, `.gif`) in the Project view, or right-click an
image's editor tab:

| Menu entry | Result |
|---|---|
| **Convert Image to… › .png** | `photo.png` next to the source |
| **Convert Image to… › .jpg › quality: 80 / 90 / 100 / custom quality…** | `photo.jpg` |
| **Convert Image to… › .webp / .avif › quality presets, lossless, custom quality…** | `photo.webp` / `photo.avif` |
| **Resize Image…** | `photo-800x600.png` (same format as the source; BMP/GIF become PNG) |

- The original is never modified. If the output exists you are asked to overwrite, keep both (`photo-1.png`) or skip, optionally for all remaining files.
- Several selected files run as one cancellable background task; a notification summarises the result. Folders in the selection are ignored.
- **Resize** takes pixels (aspect ratio lock on by default) for a single image, or a percentage for any number of images.
- EXIF and ICC data are carried over, and the EXIF orientation is applied to the pixels (the tag is reset to 1). Images in a wide-gamut colour space
  are converted to sRGB when decoded.
- Transparency is flattened onto a background colour when converting to JPG (default white, configurable in *Settings › Tools › Image Tools*).
- Animated GIF / WebP / AVIF: only the first frame can be converted for now; you're asked per batch whether to do that or skip the file.

## How it works

| Format | Implementation |
|---|---|
| PNG, JPG (BMP, GIF read-only) | JDK ImageIO; [TwelveMonkeys](https://github.com/haraldk/TwelveMonkeys) for decoding JPEG |
| WebP | [webp-imageio](https://github.com/usefulness/webp-imageio) (libwebp via JNI). Not available on Windows ARM64 |
| AVIF | libavif's `avifenc` / `avifdec` executables bundled per platform (`bin/<os>-<arch>/`): win-x64, win-arm64, mac-arm64, mac-x64, linux-x64, linux-arm64 |

All formats sit behind the `ImageCodec` interface (`codec/`), so a codec can be swapped (e.g. AVIF through an in-process binding) without touching the actions.

## Building

```shell
./gradlew test          # codec tests (AVIF tests are skipped without the native tools)
./gradlew runIde        # sandbox IDE with the plugin
./gradlew buildPlugin   # build/distributions/image-tools-<version>.zip
./gradlew verifyPlugin  # JetBrains Plugin Verifier against IDEA, WebStorm and Rider
```

Requires JDK 21.

### The bundled AVIF tools

`avifenc` / `avifdec` are not stored in git. The **Natives** workflow (`.github/workflows/natives.yml`, run manually or by pushing a
`natives-libavif-v*` tag) builds them for the six platforms and attaches them to a GitHub release. Put the printed SHA-256 sums into
`natives/checksums.txt`: the `downloadAvifNatives` task fetches the archives, verifies them against those sums and the plugin build packages them.
While that file lists no archives, the plugin builds without AVIF and the entry is simply absent from the menu.

To test against locally built binaries, point `IMAGETOOLS_NATIVES_DIR` at a directory laid out as `<dir>/<platform>/avifenc[.exe]`.

### Releasing

The template's GitHub Actions are kept: *Build* runs on every push/PR and prepares a draft release, publishing the release runs *Release*,
which signs the plugin and uploads it to JetBrains Marketplace. Secrets needed: `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`.
Create the plugin on the Marketplace manually before the first automated publish.

## License

Apache-2.0, see [LICENSE](LICENSE). Bundled components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
