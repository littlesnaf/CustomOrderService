# Mugeditor

Mugeditor is a desktop toolkit for preparing custom Amazon order artwork, labels, and packing slips at scale. The project bundles several Swing utilities and a rendering pipeline that help production teams convert Amazon Custom exports into print-ready PNGs and review supporting documents side by side.

## Contents

- **MainUI** – bulk order processor that discovers order folders, extracts ZIPs, loads fonts, and invokes the rendering pipeline.
- **LabelFinderUI** – quick search tool that matches shipping labels, packing slips, and product photos by Amazon Order ID (supports single or bulk browsing workflows).
- **OrnamentSkuUI** – splits merged ornament PDFs into per-SKU documents while preserving label + slip groupings.
- **Rendering pipeline** – utilities in `com.osman` (e.g., `ImageProcessor`, `JsonDataReader`, `PdfLinker`, `PackSlipExtractor`) used by the UIs to parse JSON, sanitize SVGs, and produce final artwork.

## Requirements

- Java 17 or newer (the build targets `--release 17`).
- Maven 3.8+ for building shaded JARs and optional Launch4J executables.
- [ImageMagick](https://imagemagick.org/) available on the PATH (`magick` CLI) for rasterizing and cleaning source images during rendering.
- Fonts directory containing the Amazon Custom font set (the default path can be overridden at runtime).
- (Optional) Tesseract OCR assets if you plan to use the Tess4J-based features.

## Building

Clone the repository and run:

```bash
mvn clean package
```

The build produces fat JARs in `target/` for each UI:

- `Mugeditor-MainUI.jar`
- `Mugeditor-LabelFinder.jar`
- `OrnamentSkuUI.jar`

If the Launch4J plugin is enabled on your platform, corresponding Windows executables (`*.exe`) are also placed in `target/`.

## Running the Applications

Each UI has its own entry point. Launch whichever tool you need:

```bash
java -jar target/Mugeditor-MainUI.jar
java -jar target/Mugeditor-LabelFinder.jar
java -jar target/OrnamentSkuUI.jar
```

### MainUI workflow

1. Choose your font directory (the UI preselects `Config.DEFAULT_FONT_DIR`).
2. After fonts load, click **Select ‘Orders’ Folder or Zip Files…** to pick one or more customer folders or ZIP archives.
3. The worker extracts embedded ZIPs, discovers leaf order folders, and renders artwork using `ImageProcessor`.
4. Output PNGs are saved under a `Ready Designs` folder inside each order/customer directory.

### LabelFinderUI workflow

- **Single order mode**: select the base “Orders” directory, enter an Amazon Order ID, and press **Find**. The tool renders the matching shipping label + packing slip stacked vertically, finds order photos, and allows printing the combined image.
- **Bulk mode**: toggle **Bulk Order (no matching)** to scan all PDF pages and photos under the base directory. Use the list on the left to browse pages, preview photos on the right, and optionally print.

### OrnamentSkuUI workflow

1. Drag merged ornament PDFs into the **Input PDFs** list (or use **Add PDFs…**).
2. Choose an output directory (the app writes results under `<output>/ready-orders/`).
3. Click **Run**. The worker splits each PDF to single pages, pairs shipping labels with packing slips, groups bundles by SKU, and writes per-SKU PDFs (`x<qty>-<sku>.pdf`). A `MIX.pdf` captures pages that contain multiple SKUs.

## Configuration Notes

- Default directories (fonts, label PDFs) are defined in `Config.java` and can be overridden with JVM system properties (e.g., `-Dosman.fontDir=/path/to/fonts`).
- The rendering pipeline expects Amazon Custom JSON + SVG exports. Ensure accompanying assets reside alongside the JSON files.
- ImageMagick must be installed where the Java process can execute `magick`; otherwise image sanitization falls back to limited behavior.

## Development

- The codebase is organized under `src/main/java/com/osman`.
- UI classes live in the `com.osman.ui` package; headless helpers live at the package root.
- Tests (if added) should go under `src/test/java`.
- Follow the existing documentation style (concise Javadoc for public classes/methods).

To run a specific UI from your IDE, set the main class accordingly (e.g., `com.osman.ui.MainUI`).

## Packaging Tips

- Because the Maven build uses the Shade plugin, `mvn package` is sufficient for distribution.
- If you need standalone executables, ensure Launch4J prerequisites are installed on the build machine.
- For macOS distribution, you can wrap the shaded JARs with Automator or use the provided `.command` launchers in the repo root.

## License

This project’s licensing has not been specified. Add licensing information here if you intend to share the code publicly.
