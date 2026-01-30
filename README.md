# CustomAmazonOrderProcessor

CustomAmazonOrderProcessor is a desktop toolkit for preparing custom Amazon order artwork, shipping labels, and packing slips at scale. The project bundles several Swing utilities plus a rendering pipeline that help production teams convert Amazon Custom exports into print-ready PNGs and review supporting documents side by side.

## Portfolio Highlights 

I built this project to practice production-style workflows and desktop tooling. It showcases:

- **Java + Swing UI engineering**: multiple desktop apps with responsive previews, background workers, and batch workflows.
- **End-to-end Amazon order handling**: downloads Amazon custom assets, categorizes orders, and prepares them for production.
- **File/asset processing pipelines**: parsing Amazon exports (TXT/JSON/SVG), sanitizing assets, and rendering print-ready PNGs.
- **PDF processing at scale**: splitting, caching, and regenerating pages for fast UI previews and high-quality printing.
- **Performance-minded design**: DPI-based preview vs. print output, caching, and batch-oriented processing to keep UI snappy.
- **Modular architecture**: shared rendering utilities across several UIs (e.g., `ImageProcessor`, `PdfLinker`, `PackSlipExtractor`).

If you are reviewing for an internship, I am happy to walk through design decisions, trade-offs, and how I tested performance and correctness.

## Contents

- **MainUI** – bulk order processor that discovers order folders, extracts ZIPs, loads fonts, and invokes the rendering pipeline.
- **AmazonImportApp** – a dedicated tool for parsing Amazon TXT exports, grouping orders by item type/shipping speed, and downloading custom artwork assets.
- **LabelFinder (App + Frame + Panel)** – quick search tool that matches shipping labels, packing slips, and product photos by Amazon Order ID. The viewer now renders previews at 100 DPI for snappy UI updates, caches pages per PDF, and regenerates 150 DPI artwork automatically when printing.
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

The build produces fat JARs in `target/` for each UI (names follow the module):

- `CustomOrderFlow-MainUI.jar`
- `CustomOrderFlow-AmazonImport.jar`
- `CustomOrderFlow-LabelFinder.jar`
- `CustomOrderFlow-OrnamentSku.jar`

If the Launch4J plugin is enabled on your platform, corresponding Windows executables (`*.exe`) are also placed in `target/`.

## Running the Applications

Each UI has its own entry point. Launch whichever tool you need:

```bash
java -jar target/CustomOrderFlow-MainUI.jar
java -jar target/CustomOrderFlow-AmazonImport.jar
java -jar target/CustomOrderFlow-LabelFinder.jar
java -jar target/CustomOrderFlow-OrnamentSku.jar
```

### MainUI workflow

1. Choose your font directory (the UI preselects `Config.DEFAULT_FONT_DIR`).
2. After fonts load, click **Select ‘Orders’ Folder or Zip Files…** to pick one or more customer folders or ZIP archives.
3. The worker extracts embedded ZIPs, discovers leaf order folders, and renders artwork using `ImageProcessor`.
4. Output PNGs are saved under a `Ready Designs` folder inside each order/customer directory.
5. A consolidated `order-quantities.json` is maintained at the batch root (two levels above the customer folders) so LabelFinder can load order and item quantities without walking every subdirectory.

### AmazonImportApp workflow

1. Launch the application.
2. Click **Open Amazon TXT…** to load an Amazon order export file.
3. The tool parses the file and groups orders by item type and shipping speed (e.g., Standard, Next Day).
4. Use the tree view to inspect grouped orders.
5. Click **Download All** or **Download Selected Item Types** to fetch custom artwork assets.
6. Use **Generate Packing Slips** to create packing slips for the batch.

### LabelFinder workflow

1. Select the base “Orders” directory (the UI persists the last choice).
2. Enter an Amazon Order ID and press **Find**. The tool looks up cached renderings first, then:
   - Renders shipping label and packing-slip previews at 100 DPI.
   - Collects matching product photos and ready-design thumbnails.
   - Displays combined artwork in the center preview.
3. Printing (manual or auto-print) regenerates the required pages at 150 DPI so thermal output stays sharp while the UI remains responsive.
4. Bulk browsing mode can still be toggled with **Bulk Order (no matching)** for manual PDF/photo exploration.

### OrnamentSkuUI workflow

1. Drag merged ornament PDFs into the **Input PDFs** list (or use **Add PDFs…**).
2. Choose an output directory (the app writes results under `<output>/ready-orders/`).
3. Click **Run**. The worker splits each PDF to single pages, pairs shipping labels with packing slips, groups bundles by SKU, and writes per-SKU PDFs (`x<qty>-<sku>.pdf`). A `MIX.pdf` captures pages that contain multiple SKUs.

## Configuration Notes

- Default directories (fonts, label PDFs) are defined in `Config.java` and can be overridden with JVM system properties (e.g., `-Dosman.fontDir=/path/to/fonts`).
- The rendering pipeline expects Amazon Custom JSON + SVG exports. Ensure accompanying assets reside alongside the JSON files.
- ImageMagick must be installed where the Java process can execute `magick`; otherwise image sanitization falls back to limited behavior.

## Development

- Source lives under `src/main/java/com/osman`.
- UI classes sit in `com.osman.ui`; shared helpers live beside them or in feature sub-packages (e.g., `com.osman.ui.labelfinder.render`).
- Tests belong under `src/test/java`. Targeted examples include `com.osman.core.render.MugRendererTest` and `com.osman.ui.labelfinder.PdfPageRenderCacheTest`.
- Use `mvn -DskipTests compile` for quick iteration, or `mvn test` to run the full suite (note: some UI-heavy tests require a graphical environment).

To run a specific UI from your IDE, set the main class accordingly (for example, `com.osman.ui.main.MainUIView` for the main processor).

## Packaging Tips

- Because the Maven build uses the Shade plugin, `mvn package` is sufficient for distribution.
- If you need standalone executables, ensure Launch4J prerequisites are installed on the build machine.
- For macOS distribution, you can wrap the shaded JARs with Automator or use the provided `.command` launchers in the repo root.
<img width="972" height="753" alt="Screenshot 2026-01-29 at 10 21 34 PM" src="https://github.com/user-attachments/assets/15562b62-bcc5-4452-904c-9823a5240439" />
<img width="2580" height="1440" alt="Demo" src="https://github.com/user-attachments/assets/a5993833-ae4c-48ac-881e-50de6f92846f" />

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
