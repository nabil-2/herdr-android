# Third-party notices

Herdr Pocket is licensed under Apache License 2.0. Herdr and Tailscale are separate host/phone installations and are not bundled by this project.

| Component | Upstream | License |
| --- | --- | --- |
| ConnectBot Terminal | [connectbot/termlib](https://github.com/connectbot/termlib) | Apache 2.0 |
| libvterm (embedded by ConnectBot Terminal) | [libvterm](https://www.leonerd.org.uk/code/libvterm/) | MIT |
| AndroidX, Jetpack Compose, CameraX | [AndroidX](https://github.com/androidx/androidx) | [Apache 2.0](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt) |
| ZXing Core (offline QR decoding) | [zxing/zxing](https://github.com/zxing/zxing) | [Apache 2.0](https://github.com/zxing/zxing/blob/master/LICENSE) |
| Multiplatform Markdown Renderer | [mikepenz/multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer) | Apache 2.0, with the included Erik Hellman MarkdownComposer MIT notice |
| JetBrains Markdown parser | [JetBrains/markdown](https://github.com/JetBrains/markdown) | Apache 2.0 |
| Native LaTeX renderer/parser 1.5.6 | [huarangmeng/latex](https://github.com/huarangmeng/latex/tree/release-1.5.6) | MIT |
| KaTeX fonts 0.16.11 (bundled by the LaTeX renderer) | [KaTeX/KaTeX](https://github.com/KaTeX/KaTeX/tree/v0.16.11/src/fonts) | MIT |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | [JetBrains/Kotlin](https://github.com/JetBrains/kotlin) | Apache 2.0 |
| OkHttp and Okio | [Square/OkHttp](https://github.com/square/okhttp) | Apache 2.0 |
| FastAPI | [fastapi/fastapi](https://github.com/fastapi/fastapi) | MIT |
| Starlette | [encode/starlette](https://github.com/encode/starlette) | BSD 3-Clause |
| Uvicorn | [encode/uvicorn](https://github.com/encode/uvicorn) | BSD 3-Clause |
| Pydantic | [pydantic/pydantic](https://github.com/pydantic/pydantic) | MIT |
| Segno (host QR generation) | [heuer/segno](https://github.com/heuer/segno) | [BSD 3-Clause](https://github.com/heuer/segno/blob/master/LICENSE) |
| Pillow (host image validation) | [python-pillow/Pillow](https://github.com/python-pillow/Pillow) | [HPND and included third-party notices](https://github.com/python-pillow/Pillow/blob/main/LICENSE) |
| Python runtime (packaged host companion) | [Python](https://www.python.org/) | [PSF License Agreement and included notices](https://docs.python.org/3/license.html) |
| PyInstaller bootloader and related files (packaged host companion) | [pyinstaller/pyinstaller](https://github.com/pyinstaller/pyinstaller) | [GPL 2.0 or later with Bootloader Exception](https://github.com/pyinstaller/pyinstaller/blob/develop/COPYING.txt) |

PyInstaller's exception permits distribution of its compiled bootloader and related files embedded in the companion executable. PyInstaller runtime hooks and additional runtime modules use Apache 2.0. The project's own app and companion code retain their Apache 2.0 license.

The full project license is in [LICENSE](LICENSE). The APK includes notices and license texts in its assets. Each packaged host release includes `LICENSES.txt` with the Python runtime and dependency distribution licenses; the executable also exposes that bundle through `herdr-pocket --licenses`. Other transitive dependency notices are included there. Dependency source and original license texts are available from the upstream projects above.
