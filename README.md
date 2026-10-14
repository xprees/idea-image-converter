# Image Tools

<!-- Plugin description -->
Convert and resize images straight from the Project view and editor tabs.

Right-click one or more images and choose <b>Convert Image to…</b> to get PNG, JPG, WebP or AVIF
(with quality presets, custom quality and lossless mode), or <b>Resize Image…</b> to scale by pixels or percentage.
EXIF orientation and colour profiles are preserved, transparent images are flattened onto a configurable
colour when converted to JPG, and originals are never touched: results are written next to the source.
<!-- Plugin description end -->

Works in JetBrains IDEs based on the IntelliJ Platform **2026.2** (IntelliJ IDEA, WebStorm, Rider, PyCharm, …). The supported range is derived from `platformVersion` in `gradle.properties`: it is built, tested and verified against that IDE version, and the plugin declares build `262` to `262.*` as compatible. Supporting a newer IDE generation means bumping that one line and re-running the verification.

## Usage

Select one or more image files (`.png`, `.jpg`/`.jpeg`, `.webp`, `.avif`, `.svg`, `.bmp`, `.gif`) in the Project view, or right-click an
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
- **SVG** can be converted to PNG, JPG, WebP or AVIF. Because a vector has no pixel size, each target offers *original size*, *×2*, *×3* and
  *custom size…* instead of the quality presets (those use the default quality from the settings; *custom size…* also asks for quality and lossless).
  The custom dialog takes a scale in percent or, for a single SVG, an exact resolution with the aspect ratio locked by default. It shows the original ratio
  and warns when you unlock it and the ratio no longer matches: the vector is then fitted inside the canvas and centred, with transparent margins
  (or the JPG background colour), never distorted. Vectors are rendered directly at the target size, so upscaling stays sharp. Scaled output is named
  `logo@2x.png` or `logo-512x512.png`. **Resize Image…** on an SVG writes a PNG.
- Animated GIF / WebP / AVIF: only the first frame can be converted for now; you're asked per batch whether to do that or skip the file.

## How it works

| Format | Implementation |
|---|---|
| SVG (read-only) | [JSVG](https://github.com/weisJ/jsvg) (pure Java). External resources are limited to files next to the SVG; nothing is fetched from the network |
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

### Versions and releases

The plugin version is `major.minor.patch`, derived from git tags:

- **Tagged commit:** the tag is the version (`0.1.3`, `v0.1.3`, or `0.2.0-beta.1`, whose suffix selects the Marketplace channel: no suffix = stable, `-beta.N` = beta).
- **Any other build:** `major.minor` come from `pluginVersionMajor` / `pluginVersionMinor` in `gradle.properties` (the `PLUGIN_VERSION_MAJOR` / `PLUGIN_VERSION_MINOR` environment variables override them; fallback `0.1`), and the patch is the next free one: the highest existing `major.minor.N` tag plus one, or `0` if there is none.
- `-PpluginVersion=x.y.z` overrides everything.

**On every merge to `master`**, once build, tests and plugin verification are green, the *Build* workflow tags the merge commit with the next version and creates a draft GitHub release for that tag. Major and minor come from the repository variables `PLUGIN_VERSION_MAJOR` and `PLUGIN_VERSION_MINOR` (*Settings → Secrets and variables → Actions → Variables*); if they are not defined, the values in `gradle.properties` are used. The patch increases automatically.

The draft release already has the built plugin attached: `image-tools-<version>.zip`, plus `image-tools-<version>-signed.zip` when the signing secrets (`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`) are configured. That zip is also what you upload by hand for the very first Marketplace release.

To start a new line (say `0.2`), change the two variables; the next merge is tagged `0.2.0`. To ship a build, publish its draft release: the *Release* workflow then builds that exact tag, signs it and publishes it to the Marketplace. Signing and publishing are refused for builds that aren't from a tag. For a beta, edit the draft's tag before publishing (e.g. `0.2.0-beta.1`).

## License

Apache-2.0, see [LICENSE](LICENSE). Bundled components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
