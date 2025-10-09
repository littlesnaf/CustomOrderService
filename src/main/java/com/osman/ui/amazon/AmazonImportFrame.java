package com.osman.ui.amazon;

import com.osman.integration.amazon.AmazonOrderDownloadService;
import com.osman.integration.amazon.AmazonOrderGroupingService;
import com.osman.integration.amazon.AmazonOrderRecord;
import com.osman.integration.amazon.AmazonTxtOrderParser;
import com.osman.integration.amazon.CustomerGroup;
import com.osman.integration.amazon.CustomerOrder;
import com.osman.integration.amazon.CustomerOrderItem;
import com.osman.integration.amazon.ItemTypeGroup;
import com.osman.integration.amazon.OrderBatch;
import com.osman.integration.amazon.OrderImportSession;
import com.osman.logging.AppLogger;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

/**
 * Standalone Swing UI for importing Amazon TXT order files and managing grouped downloads.
 */
public class AmazonImportFrame extends JFrame {
    private static final Logger LOGGER = AppLogger.get();
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AmazonTxtOrderParser parser = new AmazonTxtOrderParser();
    private final AmazonOrderGroupingService groupingService = new AmazonOrderGroupingService();
    private final AmazonOrderDownloadService downloadService = new AmazonOrderDownloadService();
    private final OrderImportSession session = OrderImportSession.getInstance();

    private final JLabel fileLabel = new JLabel("No file loaded");
    private final JLabel statusLabel = new JLabel("Idle");
    private final JButton loadButton = new JButton("Open Amazon TXT…");
    private final JButton downloadAllButton = new JButton("Download All");
    private final JButton downloadSelectedButton = new JButton("Download Selected Item Type");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextArea logArea = new JTextArea();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("No data"));
    private final JTree orderTree = new JTree(treeModel);

    private volatile boolean busy = false;

    public AmazonImportFrame() {
        super("Amazon TXT Import");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setPreferredSize(new Dimension(960, 700));
        buildUi();
        attachListeners();
    }

    private void buildUi() {
        setLayout(new BorderLayout(8, 8));

        JPanel topPanel = new JPanel(new BorderLayout(8, 8));
        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        fileRow.add(loadButton);
        fileRow.add(fileLabel);
        topPanel.add(fileRow, BorderLayout.NORTH);

        JPanel actionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        actionsRow.add(downloadAllButton);
        actionsRow.add(downloadSelectedButton);
        actionsRow.add(statusLabel);
        topPanel.add(actionsRow, BorderLayout.SOUTH);

        add(topPanel, BorderLayout.NORTH);

        orderTree.setShowsRootHandles(true);
        orderTree.setRootVisible(true);
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
                    Optional<String> selectedItemType = resolveSelectedItemType();
                    if (selectedItemType.isEmpty()) {
                        showMessage("Select an item type in the tree to download.");
                        return;
                    }
                    startDownload(batch, selectedItemType);
                },
                () -> showMessage("Load a TXT file before downloading.")
            ));
    }

    private void chooseFileAndParse() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Amazon TXT export");
        int result = chooser.showOpenDialog(this);
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
                publish("Parsed %d rows.".formatted(records.size()));

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
                chunks.forEach(AmazonImportFrame.this::appendLog);
            }

            @Override
            protected void done() {
                try {
                    OrderBatch batch = get();
                    session.store(file, batch);
                    updateTree(batch);
                    fileLabel.setText(file.toAbsolutePath().toString());
                    statusLabel.setText("Loaded at " + TIMESTAMP_FORMATTER.format(session.getLoadedAt().orElseThrow()));
                    downloadAllButton.setEnabled(!batch.isEmpty());
                    downloadSelectedButton.setEnabled(!batch.isEmpty());
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

    private void startDownload(OrderBatch batch, Optional<String> itemType) {
        if (busy) return;
        busy = true;
        setUiEnabled(false);

        DownloadWorker worker = new DownloadWorker(batch, itemType);
        worker.execute();
    }

    private Optional<String> resolveSelectedItemType() {
        TreePath selection = orderTree.getSelectionPath();
        if (selection == null) {
            return Optional.empty();
        }

        Object lastComponent = ((DefaultMutableTreeNode) selection.getLastPathComponent()).getUserObject();
        if (lastComponent instanceof ItemTypeNodeData data) {
            return Optional.of(data.itemType());
        }

        // If a child node is selected, try to bubble up to the parent item type.
        for (Object pathComponent : selection.getPath()) {
            if (pathComponent instanceof DefaultMutableTreeNode node
                && node.getUserObject() instanceof ItemTypeNodeData data) {
                return Optional.of(data.itemType());
            }
        }

        return Optional.empty();
    }

    private void updateTree(OrderBatch batch) {
        if (batch == null || batch.isEmpty()) {
            resetTree();
            return;
        }

        DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode("Item Types: " + batch.groups().size());

        for (Map.Entry<String, ItemTypeGroup> entry : batch.groups().entrySet()) {
            String itemType = entry.getKey();
            ItemTypeGroup group = entry.getValue();
            int totalItems = group.customers().values().stream()
                .mapToInt(customer -> customer.orders().values().stream()
                    .mapToInt(order -> order.items().size())
                    .sum())
                .sum();

            DefaultMutableTreeNode itemNode = new DefaultMutableTreeNode(new ItemTypeNodeData(itemType, totalItems));

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

            newRoot.add(itemNode);
        }

        treeModel.setRoot(newRoot);
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
        progressBar.setEnabled(enabled);
    }

    private void appendLog(String message) {
        if (message == null || message.isBlank()) return;
        logArea.append(message + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(this, message, "Amazon TXT Import", JOptionPane.INFORMATION_MESSAGE);
    }

    private record ItemTypeNodeData(String itemType, int itemCount) {
        @Override
        public String toString() {
            return "%s (%d items)".formatted(itemType, itemCount);
        }
    }

    private final class DownloadWorker extends SwingWorker<Path, DownloadStatus>
        implements AmazonOrderDownloadService.DownloadProgressListener {

        private final OrderBatch batch;
        private final Optional<String> itemType;
        private int totalItems;

        private DownloadWorker(OrderBatch batch, Optional<String> itemType) {
            this.batch = Objects.requireNonNull(batch);
            this.itemType = Objects.requireNonNull(itemType);
        }

        @Override
        protected Path doInBackground() throws Exception {
            logArea.append("Starting download…" + System.lineSeparator());
            if (itemType.isPresent()) {
                return downloadService.downloadItemType(batch, itemType.get(), this);
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
            publish(new DownloadStatus(processedCount, totalCount,
                "Failed item %s: %s".formatted(item.orderItemId(), error.getMessage())));
        }

        @Override
        public void onComplete(int processedCount, int totalCount, Path targetRoot) {
            publish(new DownloadStatus(processedCount, totalCount,
                "Download finished. Files at " + targetRoot));
        }
    }

    private record DownloadStatus(int processed, int total, String description) {
    }
}
