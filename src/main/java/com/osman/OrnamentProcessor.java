package com.osman;

import com.osman.PackSlipExtractor;
import com.osman.PdfLinker;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import java.awt.image.BufferedImage;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OrnamentProcessor {

    private static final Pattern SKU_RE = Pattern.compile("\\bSKU:\\s*([\\w./\\-]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Ana API:
     * - labelsPdf ve slipsPdf’i tarar
     * - Siparişleri SKU kümelerine göre gruplar
     * - Her grup için ayrı bir PDF üretir
     * @return skuKey -> çıktı dosyası
     */
    public static Map<String, File> generateSkuPdfs(File labelsPdf, File slipsPdf, boolean includeUnmatched) throws Exception {
        if (labelsPdf == null || !labelsPdf.isFile()) throw new IOException("Labels PDF not found");
        if (slipsPdf == null || !slipsPdf.isFile()) throw new IOException("Slips PDF not found");

        // 1) Indexler
        Map<String, List<Integer>> labelMap = PdfLinker.buildOrderIdToPagesMap(labelsPdf);
        Map<String, List<Integer>> slipMap  = PackSlipExtractor.indexOrderToPages(slipsPdf);

        // 2) Sliplerden SKU setleri (orderId -> {sku...})
        Map<String, Set<String>> orderToSkus = extractSkusPerOrder(slipsPdf, slipMap);

        // 3) Hangi order’lar işlenecek?
        LinkedHashSet<String> orderIds = new LinkedHashSet<>();
        orderIds.addAll(labelMap.keySet());
        orderIds.addAll(slipMap.keySet());

        if (!includeUnmatched) {
            orderIds.removeIf(id -> !(labelMap.containsKey(id) && slipMap.containsKey(id)));
        }

        if (orderIds.isEmpty()) return Collections.emptyMap();

        // 4) Gruplandırma: skuKey -> orderId listesi
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String id : orderIds) {
            Set<String> skus = orderToSkus.getOrDefault(id, Collections.emptySet());
            String key;
            if (skus.isEmpty()) {
                key = includeUnmatched ? "UNKNOWN" : null; // unmatched ve slip yoksa UNKNOWN’a koy
            } else if (skus.size() == 1) {
                key = sanitizeSku(skus.iterator().next());
            } else {
                key = "MIX";
            }
            if (key != null) {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
            }
        }

        // 5) Çıktı üretimi: her grup için tek PDF
        Map<String, File> result = new LinkedHashMap<>();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File outDir = resolveOrdersDir(labelsPdf);

        try (PDDocument labelsDoc = PDDocument.load(labelsPdf);
             PDDocument slipsDoc  = PDDocument.load(slipsPdf)) {

            PDFRenderer labelRenderer = new PDFRenderer(labelsDoc);
            PDFRenderer slipRenderer  = new PDFRenderer(slipsDoc);

            for (Map.Entry<String, List<String>> g : groups.entrySet()) {
                String skuKey = g.getKey();
                List<String> ids = g.getValue();
                if (ids.isEmpty()) continue;

                File outFile = new File(outDir, skuKey +".pdf");
                try (PDDocument outDoc = new PDDocument()) {
                    final int dpi = 150;

                    for (String id : ids) {
                        // label sayfaları (varsa)
                        for (int p : labelMap.getOrDefault(id, Collections.emptyList())) {
                            addRenderedPage(labelRenderer, p, dpi, outDoc);
                        }
                        // slip sayfaları (varsa)
                        for (int p : slipMap.getOrDefault(id, Collections.emptyList())) {
                            addRenderedPage(slipRenderer, p, dpi, outDoc);
                        }
                    }

                    outDoc.save(outFile);
                }
                result.put(skuKey, outFile);
            }
        }

        return result;
    }
    private static File resolveOrdersDir(File referencePdf) {
        // Kaynak PDF’in bulunduğu klasörün altına "Orders" klasörü aç
        File base = referencePdf.getParentFile() != null ? referencePdf.getParentFile() : new File(".");
        File orders = new File(base, "Orders");
        if (!orders.exists()) orders.mkdirs();
        return orders;
    }
    /** Slip PDF’i içinden, slipMap’teki sayfaları baz alarak SKU’ları toplar (orderId -> Set<SKU>) */
    private static Map<String, Set<String>> extractSkusPerOrder(File slipsPdf, Map<String, List<Integer>> slipMap) throws IOException {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (slipMap.isEmpty()) return out;

        try (PDDocument doc = PDDocument.load(slipsPdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            for (Map.Entry<String, List<Integer>> e : slipMap.entrySet()) {
                String orderId = e.getKey();
                Set<String> skus = new LinkedHashSet<>();
                for (int p : e.getValue()) {
                    stripper.setStartPage(p);
                    stripper.setEndPage(p);
                    String text = normalize(stripper.getText(doc));
                    // sadece “SKU:” kalıplarını topla (footer bağımsız)
                    Matcher m = SKU_RE.matcher(text);
                    while (m.find()) {
                        String sku = m.group(1).trim();
                        if (!sku.isEmpty()) skus.add(sku);
                    }
                }
                if (!skus.isEmpty()) {
                    out.put(orderId, skus);
                }
            }
        }
        return out;
    }

    private static String sanitizeSku(String sku) {
        // dosya adı güvenliği için
        String s = sku.replaceAll("[^\\w\\-\\.]+", "_");
        if (s.length() > 80) s = s.substring(0, 80);
        return s.isEmpty() ? "UNKNOWN" : s;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        s = s.replace('\u00A0', ' ');
        s = s.replace('–', '-').replace('—', '-');
        s = s.replaceAll("[ \\t]+", " ");
        return Normalizer.normalize(s, Normalizer.Form.NFKC);
    }

    /** Kaynak PDF’teki tek sayfayı, çıktı PDF’ine ayrı bir LETTER sayfa olarak rasterize edip ekler. */
    private static void addRenderedPage(PDFRenderer renderer, int page1Based, int dpi, PDDocument outDoc) throws IOException {
        BufferedImage img = renderer.renderImageWithDPI(page1Based - 1, dpi);
        if (img == null) return;

        PDPage page = new PDPage(PDRectangle.LETTER);
        outDoc.addPage(page);

        float margin = 18f;
        float pw = page.getMediaBox().getWidth()  - margin * 2;
        float ph = page.getMediaBox().getHeight() - margin * 2;

        float scale = Math.min(pw / img.getWidth(), ph / img.getHeight());
        int drawW = Math.max(1, Math.round(img.getWidth()  * scale));
        int drawH = Math.max(1, Math.round(img.getHeight() * scale));

        int dx = Math.max(0, Math.round((pw - drawW) / 2f));
        int dy = Math.max(0, Math.round((ph - drawH) / 2f));

        try (PDPageContentStream cs = new PDPageContentStream(outDoc, page)) {
            var pdImg = LosslessFactory.createFromImage(outDoc, img);
            cs.drawImage(pdImg, margin + dx, margin + dy, drawW, drawH);
        }
    }
}
