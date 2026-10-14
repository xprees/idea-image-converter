# Third-party notices

Image Tools itself is licensed under the Apache License 2.0 (see `LICENSE`). It bundles or runs the following components.

| Component | Used for | License |
|---|---|---|
| [libavif](https://github.com/AOMediaCodec/libavif) `avifenc` / `avifdec` | AVIF encode and decode (separate executables shipped in `bin/<platform>/`) | BSD-2-Clause |
| [libaom](https://aomedia.googlesource.com/aom/) (linked into those executables) | AV1 codec | BSD-2-Clause + AOMedia Patent License 1.0 |
| [zlib](https://zlib.net/), [libpng](http://www.libpng.org/pub/png/libpng.html) (linked into those executables) | PNG hand-off to the AVIF tools | zlib license, libpng license |
| [webp-imageio](https://github.com/usefulness/webp-imageio) (bundles libwebp) | WebP encode and decode | Apache-2.0 (libwebp: BSD-3-Clause) |
| [JSVG](https://github.com/weisJ/jsvg) | Rendering SVG images | MIT |
| [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) (`imageio-jpeg` and its core modules) | Robust JPEG decoding (CMYK, unusual JFIF/Exif) | BSD-3-Clause |

The full license texts of the native components ship next to the executables (`bin/<platform>/LICENSE-*`, `PATENTS-libaom`).
