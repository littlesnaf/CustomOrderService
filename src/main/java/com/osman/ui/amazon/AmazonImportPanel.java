package com.osman.ui.amazon;

import com.osman.integration.amazon.AmazonOrderDownloadService;
import com.osman.integration.amazon.AmazonOrderGroupingService;
import com.osman.integration.amazon.AmazonOrderRecord;
import com.osman.integration.amazon.AmazonPackingSlipGenerator;
import com.osman.integration.amazon.AmazonTxtOrderParser;
import com.osman.integration.amazon.CustomerGroup;
import com.osman.integration.amazon.CustomerOrder;
import com.osman.integration.amazon.CustomerOrderItem;
import com.osman.integration.amazon.ItemTypeCategorizer;
import com.osman.integration.amazon.ItemTypeGroup;
import com.osman.integration.amazon.OrderBatch;
import com.osman.integration.amazon.OrderImportSession;
import com.osman.logging.AppLogger;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Panel that hosts the Amazon TXT import workflow. Designed for embedding inside other views.
 */
public class AmazonImportPanel extends JPanel {
    private static final Logger LOGGER = AppLogger.get();
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AmazonTxtOrderParser parser = new AmazonTxtOrderParser();
    private final AmazonOrderGroupingService groupingService = new AmazonOrderGroupingService();
    private final AmazonOrderDownloadService downloadService = new AmazonOrderDownloadService();
    private final AmazonPackingSlipGenerator packingSlipGenerator = new AmazonPackingSlipGenerator();
    private final OrderImportSession session = OrderImportSession.getInstance();

    private final JLabel fileLabel = new JLabel("No file loaded");
    private final JLabel statusLabel = new JLabel("Idle");
    private final JButton loadButton = new JButton("Open Amazon TXT…");
    private final JButton downloadAllButton = new JButton("Download All");
    private final JButton downloadSelectedButton = new JButton("Download Selected Item Types");
    private final JButton generatePackingSlipsButton = new JButton("Generate Packing Slips");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextArea logArea = new JTextArea();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("No data"));
    private final JTree orderTree = new JTree(treeModel);

    private volatile boolean busy = false;
    private Path lastDownloadRoot;

    public AmazonImportPanel() {
        setLayout(new BorderLayout(8, 8));
        buildUi();
        attachListeners();
    }

    private void buildUi() {
        JPanel topPanel = new JPanel(new BorderLayout(8, 8));
        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        fileRow.add(loadButton);
        fileRow.add(fileLabel);
        topPanel.add(fileRow, BorderLayout.NORTH);

        JPanel actionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        actionsRow.add(downloadAllButton);
        actionsRow.add(downloadSelectedButton);
        actionsRow.add(generatePackingSlipsButton);
        actionsRow.add(statusLabel);
        topPanel.add(actionsRow, BorderLayout.SOUTH);

        add(topPanel, BorderLayout.NORTH);

        orderTree.setShowsRootHandles(true);
        orderTree.setRootVisible(true);
        orderTree.getSelectionModel().setSelectionMode(TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        JScrollPane treeScroll = new JScrollPane(orderTree);
        treeScroll.setPreferredSize(new Dimension(400, 400));

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(400, 200));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeScroll, logScroll);
        splitPane.setResizeWeight(0.65);
        add(splitPane, BorderLayout.CENTER);

        progressBar.setStringPainted(true);
        progressBar.setValue(0);
        add(progressBar, BorderLayout.SOUTH);

        downloadAllButton.setEnabled(false);
        downloadSelectedButton.setEnabled(false);
        generatePackingSlipsButton.setEnabled(false);
    }

    private void attachListeners() {
        loadButton.addActionListener(e -> chooseFileAndParse());

        downloadAllButton.addActionListener(e -> session.getCurrentBatch()
            .ifPresentOrElse(
                batch -> startDownload(batch, Optional.empty()),
                () -> showMessage("Load a TXT file before downloading.")
            ));

        downloadSelectedButton.addActionListener(e -> session.getCurrentBatch()
            .ifPresentOrElse(
                batch -> {
                    Set<String> selectedItemTypes = resolveSelectedItemTypes();
                    if (selectedItemTypes.isEmpty()) {
                        showMessage("Select one or more item types in the tree to download.");
                        return;
                    }
                    startDownload(batch, Optional.of(selectedItemTypes));
                },
                () -> showMessage("Load a TXT file before downloading.")
            ));

        generatePackingSlipsButton.addActionListener(e -> session.getCurrentBatch()
            .ifPresentOrElse(
                this::generatePackingSlips,
                () -> showMessage("Load a TXT file before generating packing slips.")
            ));
    }

    private void chooseFileAndParse() {
        if (busy) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Amazon TXT export");
        int result = chooser.showOpenDialog(getParentWindow());
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path selected = chooser.getSelectedFile().toPath();
        parseFile(selected);
    }

    private void parseFile(Path file) {
        if (busy) return;
        busy = true;
        setUiEnabled(false);
        appendLog("Parsing " + file.getFileName());

        new SwingWorker<OrderBatch, String>() {
            @Override
            protected OrderBatch doInBackground() throws Exception {
                publish("Reading file…");
                List<AmazonOrderRecord> records = parser.parse(file);
                int skippedNonLateShipment = parser.getLastSkippedNonLateShipmentCount();
                List<String> lateShipmentOrders = parser.getLastLateShipmentOrderIds();
                publish("Parsed %d rows.".formatted(records.size()));
                if (skippedNonLateShipment > 0) {
                    publish("Skipped %d rows without verge-of-lateShipment flag.".formatted(skippedNonLateShipment));
                }
                if (!lateShipmentOrders.isEmpty()) {
                    publish("Late-shipment orders: " + String.join(", ", lateShipmentOrders));
                }

                OrderBatch batch = groupingService.group(records);
                if (batch.isEmpty()) {
                    publish("No valid rows detected.");
                } else {
                    publish("Grouped into %d item types.".formatted(batch.groups().size()));
                }
                return batch;
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(AmazonImportPanel.this::appendLog);
            }

            @Override
            protected void done() {
                try {
                    OrderBatch batch = get();
                    session.store(file, batch);
                    updateTree(batch);
                    fileLabel.setText(file.toAbsolutePath().toString());
                    session.getLoadedAt().ifPresentOrElse(
                        instant -> statusLabel.setText("Loaded at " + TIMESTAMP_FORMATTER.format(instant.atZone(ZoneId.systemDefault()))),
                        () -> statusLabel.setText("Loaded.")
                    );
                    downloadAllButton.setEnabled(!batch.isEmpty());
                    downloadSelectedButton.setEnabled(!batch.isEmpty());
                    generatePackingSlipsButton.setEnabled(!batch.isEmpty());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    appendLog("Parsing interrupted.");
                } catch (ExecutionException ex) {
                    appendLog("Failed to parse: " + ex.getCause().getMessage());
                    LOGGER.warning("Parsing failed: " + ex.getCause());
                    resetTree();
                } finally {
                    busy = false;
                    setUiEnabled(true);
                    progressBar.setValue(0);
                }
            }
        }.execute();
    }

    private void startDownload(OrderBatch batch, Optional<Set<String>> itemTypes) {
        if (busy) return;
        busy = true;
        setUiEnabled(false);

        String logMessage = itemTypes.filter(types -> !types.isEmpty())
            .map(types -> "Downloading selected item types: " + types.stream()
                .map(key -> Optional.ofNullable(batch.groups().get(key))
                    .map(ItemTypeGroup::itemType)
                    .orElse(key))
                .collect(Collectors.joining(", ")))
            .orElse("Downloading entire batch");
        appendLog(logMessage);

        DownloadWorker worker = new DownloadWorker(batch, itemTypes);
        worker.execute();
    }

    private void generatePackingSlips(OrderBatch batch) {
        if (busy) {
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Amazon download root (contains item type folders)");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (lastDownloadRoot != null && Files.exists(lastDownloadRoot)) {
            chooser.setCurrentDirectory(lastDownloadRoot.toFile());
            chooser.setSelectedFile(lastDownloadRoot.toFile());
        }
        int result = chooser.showSaveDialog(getParentWindow());
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path outputDir = chooser.getSelectedFile().toPath();
        busy = true;
        setUiEnabled(false);
        progressBar.setIndeterminate(true);
        progressBar.setString("Generating packing slips…");

        new SwingWorker<List<Path>, String>() {
            @Override
            protected List<Path> doInBackground() throws Exception {
                publish("Generating packing slips into " + outputDir);
                List<Path> generated = packingSlipGenerator.generatePackingSlips(batch, outputDir);
                for (Path slip : generated) {
                    publish("Created slip: " + slip.getFileName());
                }
                return generated;
            }

            @Override
            protected void process(List<String> chunks) {
                chunks.forEach(AmazonImportPanel.this::appendLog);
            }

            @Override
            protected void done() {
                try {
                    List<Path> generated = get();
                    appendLog("Packing slips generated: " + generated.size());
                    statusLabel.setText("Packing slips at " + outputDir);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    appendLog("Packing slip generation interrupted.");
                } catch (ExecutionException ex) {
                    appendLog("Failed to generate packing slips: " + ex.getCause().getMessage());
                    LOGGER.warning("Packing slip generation failed: " + ex.getCause());
                } finally {
                    busy = false;
                    setUiEnabled(true);
                    progressBar.setIndeterminate(false);
                    progressBar.setString("");
                }
            }
        }.execute();
    }

    private Set<String> resolveSelectedItemTypes() {
        TreePath[] selections = orderTree.getSelectionPaths();
        if (selections == null || selections.length == 0) {
            return Set.of();
        }

        Set<String> selectedKeys = new LinkedHashSet<>();
        for (TreePath selection : selections) {
            Object last = selection.getLastPathComponent();
            if (!(last instanceof DefaultMutableTreeNode node)) {
                continue;
            }
            Object userObject = node.getUserObject();
            if (userObject instanceof ItemTypeNodeData data) {
                selectedKeys.add(data.groupKey());
            }
        }
        return selectedKeys;
    }

    private void updateTree(OrderBatch batch) {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Amazon Orders");
        DefaultMutableTreeNode mugsNode = new DefaultMutableTreeNode(ItemTypeCategorizer.MUGS_FOLDER_NAME);

        Map<String, ItemTypeGroup> groups = batch.groups();
        for (Map.Entry<String, ItemTypeGroup> entry : groups.entrySet()) {
            String groupKey = entry.getKey();
            ItemTypeGroup group = entry.getValue();
            if (group == null || group.isEmpty()) {
                continue;
            }

            int totalItems = group.customers().values().stream()
                .mapToInt(customer -> customer.orders().values().stream()
                    .mapToInt(order -> order.items().size())
                    .sum())
                .sum();

            DefaultMutableTreeNode itemNode = new DefaultMutableTreeNode(
                new ItemTypeNodeData(groupKey, group.itemType(), totalItems)
            );

            for (CustomerGroup customer : group.customers().values()) {
                DefaultMutableTreeNode customerNode = new DefaultMutableTreeNode(
                    "%s (%d orders)".formatted(customer.originalBuyerName(), customer.orders().size())
                );
                for (CustomerOrder order : customer.orders().values()) {
                    DefaultMutableTreeNode orderNode = new DefaultMutableTreeNode("Order " + order.orderId());
                    for (CustomerOrderItem item : order.items()) {
                        orderNode.add(new DefaultMutableTreeNode("Item " + item.orderItemId()));
                    }
                    customerNode.add(orderNode);
                }
                itemNode.add(customerNode);
            }

            mugsNode.add(itemNode);
        }

        if (mugsNode.getChildCount() == 0) {
            resetTree();
            return;
        }

        mugsNode.setUserObject("%s (%d types)".formatted(
            ItemTypeCategorizer.MUGS_FOLDER_NAME,
            mugsNode.getChildCount()
        ));

        treeModel.setRoot(mugsNode);
        for (int i = 0; i < orderTree.getRowCount(); i++) {
            orderTree.expandRow(i);
        }
        orderTree.setRootVisible(true);
    }

    private void resetTree() {
        treeModel.setRoot(new DefaultMutableTreeNode("No data"));
        orderTree.setRootVisible(true);
    }

    private void setUiEnabled(boolean enabled) {
        loadButton.setEnabled(enabled);
        Object root = treeModel.getRoot();
        boolean hasData = root instanceof DefaultMutableTreeNode node && node.getChildCount() > 0;
        downloadAllButton.setEnabled(enabled && hasData);
        downloadSelectedButton.setEnabled(enabled && hasData);
        generatePackingSlipsButton.setEnabled(enabled && hasData);
        progressBar.setEnabled(enabled);
    }

    private void appendLog(String message) {
        if (message == null || message.isBlank()) return;
        logArea.append(message + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(getParentWindow(), message, "Amazon TXT Import", JOptionPane.INFORMATION_MESSAGE);
    }

    private Component getParentWindow() {
        return SwingUtilities.getWindowAncestor(this);
    }

    private record ItemTypeNodeData(String groupKey, String itemType, int itemCount) {
        @Override
        public String toString() {
            return "%s (%d items)".formatted(itemType, itemCount);
        }
    }

    private final class DownloadWorker extends SwingWorker<Path, DownloadStatus>
        implements AmazonOrderDownloadService.DownloadProgressListener {

        private final OrderBatch batch;
        private final Optional<Set<String>> itemTypes;
        private int totalItems;
        private final Set<String> failedOrderIds = new LinkedHashSet<>();
        private final List<String> failedItemsWithoutOrder = new ArrayList<>();
        private Path targetRoot;

        private DownloadWorker(OrderBatch batch, Optional<Set<String>> itemTypes) {
            this.batch = Objects.requireNonNull(batch);
            this.itemTypes = Objects.requireNonNull(itemTypes);
        }

        @Override
        protected Path doInBackground() throws Exception {
            logArea.append("Starting download…" + System.lineSeparator());
            if (itemTypes.isPresent() && !itemTypes.get().isEmpty()) {
                return downloadService.downloadItemTypes(batch, itemTypes.get(), this);
            }
            return downloadService.downloadBatch(batch, this);
        }

        @Override
        protected void process(List<DownloadStatus> chunks) {
            for (DownloadStatus status : chunks) {
                progressBar.setMaximum(Math.max(1, status.total()));
                progressBar.setValue(Math.min(status.total(), status.processed()));
                progressBar.setString(status.description());
                appendLog(status.description());
            }
        }

        @Override
        protected void done() {
            try {
                Path result = get();
                appendLog("Download complete at " + result);
                statusLabel.setText("Finished. Output: " + result);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                appendLog("Download interrupted.");
            } catch (ExecutionException ex) {
                appendLog("Download failed: " + ex.getCause().getMessage());
                LOGGER.warning("Download failed: " + ex.getCause());
            } finally {
                progressBar.setValue(0);
                progressBar.setString("");
                busy = false;
                setUiEnabled(true);
            }
        }

        @Override
        public void onStart(int totalItems, Path targetRoot) {
            this.totalItems = totalItems;
            this.targetRoot = targetRoot;
            publish(new DownloadStatus(0, totalItems, "Preparing download folder: " + targetRoot));
        }

        @Override
        public void onItemStarted(CustomerOrderItem item, int processedCount, int totalCount) {
            publish(new DownloadStatus(processedCount - 1, totalCount,
                "Downloading item %s".formatted(item.orderItemId())));
        }

        @Override
        public void onItemCompleted(CustomerOrderItem item, Path downloadedFile, int processedCount, int totalCount) {
            publish(new DownloadStatus(processedCount, totalCount,
                "Saved %s".formatted(downloadedFile.getFileName())));
        }

        @Override
        public void onItemFailed(CustomerOrderItem item, Exception error, int processedCount, int totalCount) {
            String orderId = null;
            if (item.sourceRecord() != null) {
                orderId = item.sourceRecord().orderId();
            }
            String descriptor = orderId == null
                ? item.orderItemId()
                : "%s (order %s)".formatted(item.orderItemId(), orderId);

            if (orderId != null && !orderId.isBlank()) {
                failedOrderIds.add(orderId);
            } else {
                failedItemsWithoutOrder.add(item.orderItemId());
            }

            publish(new DownloadStatus(processedCount, totalCount,
                "Failed item %s: %s".formatted(descriptor, error.getMessage())));
        }

        @Override
        public void onComplete(int processedCount, int totalCount, Path targetRoot) {
            publish(new DownloadStatus(processedCount, totalCount,
                "Download finished. Files at " + targetRoot));

            AmazonImportPanel.this.lastDownloadRoot = targetRoot;

            if (!failedOrderIds.isEmpty() || !failedItemsWithoutOrder.isEmpty()) {
                StringBuilder summary = new StringBuilder();
                if (!failedOrderIds.isEmpty()) {
                    summary.append("Failed orders: ")
                        .append(String.join(", ", failedOrderIds));
                }
                if (!failedItemsWithoutOrder.isEmpty()) {
                    if (summary.length() > 0) {
                        summary.append("; ");
                    }
                    summary.append("Failed items without order id: ")
                        .append(String.join(", ", failedItemsWithoutOrder));
                }
                publish(new DownloadStatus(processedCount, totalCount, summary.toString()));
            }
        }
    }

    private record DownloadStatus(int processed, int total, String description) {
    }
}
