package com.osman.ui.amazon;

import com.osman.integration.amazon.AmazonOrderDownloadService;
import com.osman.integration.amazon.AmazonOrderGroupingService;
import com.osman.integration.amazon.AmazonOrderRecord;
import com.osman.integration.amazon.AmazonPackingSlipGenerator;
import com.osman.integration.amazon.AmazonTxtOrderParser;
import com.osman.integration.amazon.CustomerGroup;
import com.osman.integration.amazon.CustomerOrder;
import com.osman.integration.amazon.CustomerOrderItem;
import com.osman.integration.amazon.ShippingLayoutPlanner;
import com.osman.integration.amazon.ShippingLayoutPlanner.MixMetadata;
import com.osman.integration.amazon.ShippingLayoutPlanner.ShippingSpeed;
import com.osman.integration.amazon.ItemTypeCategorizer;
import com.osman.integration.amazon.ItemTypeGroup;
import com.osman.integration.amazon.OrderBatch;
import com.osman.integration.amazon.OrderImportSession;
import com.osman.logging.AppLogger;

import javax.swing.JButton;
import javax.swing.JCheckBox;
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
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
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
    private final JCheckBox includeLateToggle = new JCheckBox("Next Day Orders");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextArea logArea = new JTextArea();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode("No data"));
    private final JTree orderTree = new JTree(treeModel);

    private volatile boolean busy = false;
    private Path lastDownloadRoot;
    private final JPanel bannerPanel = new JPanel(new BorderLayout());
    private final JLabel bannerLabel = new JLabel("Idle");
    private Timer bannerResetTimer;
    private boolean includeLateShipments = false;

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
        includeLateToggle.setToolTipText("When selected, include Next-Day orders.");
        actionsRow.add(includeLateToggle);
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

        configureBanner();

        JPanel centerWrapper = new JPanel(new BorderLayout(8, 8));
        centerWrapper.add(bannerPanel, BorderLayout.NORTH);
        centerWrapper.add(splitPane, BorderLayout.CENTER);
        add(centerWrapper, BorderLayout.CENTER);

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

        includeLateToggle.addItemListener(e -> {
            includeLateShipments = includeLateToggle.isSelected();
            parser.setIncludeLateShipmentRows(includeLateShipments);
            if (includeLateShipments) {
                showBanner("Including Next-Day orders.", BannerStyle.INFO, 4000);
            } else {
                showBanner("Filtering to verge-of-lateShipment = false only.", BannerStyle.INFO, 4000);
            }
        });
        includeLateToggle.setSelected(includeLateShipments);
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
        final boolean includeLate = includeLateShipments;
        parser.setIncludeLateShipmentRows(includeLate);
        busy = true;
        setUiEnabled(false);
        appendLog("Parsing " + file.getFileName());
        showBanner("Parsing " + file.getFileName(), BannerStyle.INFO, 3000);
        progressBar.setIndeterminate(true);
        progressBar.setString("Parsing TXT…");

        new SwingWorker<OrderBatch, String>() {
            @Override
            protected OrderBatch doInBackground() throws Exception {
                publish(includeLate ? "Including Next-Day orders." : "Filtering to verge-of-lateShipment = false only.");
                publish("Reading file…");
                List<AmazonOrderRecord> records = parser.parse(file);
                int skippedLateShipment = parser.getLastSkippedLateShipmentCount();
                List<String> lateShipmentOrders = parser.getLastLateShipmentOrderIds();
                publish("Parsed %d rows.".formatted(records.size()));
                if (!parser.isIncludeLateShipmentRows() && skippedLateShipment > 0) {
                    publish("Skipped %d rows marked Next-Day Orders.".formatted(skippedLateShipment));
                }
                if (!lateShipmentOrders.isEmpty()) {
                    publish("Next-Day Orders: " + String.join(", ", lateShipmentOrders));
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
                    if (batch.isEmpty()) {
                        showBanner("TXT import produced no mug orders.", BannerStyle.WARNING, 5000);
                    } else {
                        showBanner("TXT import complete – " + batch.groups().size() + " item types ready.", BannerStyle.SUCCESS, 5000);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    appendLog("Parsing interrupted.");
                    showBanner("TXT parsing interrupted", BannerStyle.WARNING, 4000);
                } catch (ExecutionException ex) {
                    appendLog("Failed to parse: " + ex.getCause().getMessage());
                    LOGGER.warning("Parsing failed: " + ex.getCause());
                    resetTree();
                    showBanner("TXT parsing failed: " + ex.getCause().getMessage(), BannerStyle.ERROR, 6000);
                } finally {
                    busy = false;
                    setUiEnabled(true);
                    progressBar.setIndeterminate(false);
                    progressBar.setValue(0);
                    progressBar.setString("");
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
        showBanner("Starting download…", BannerStyle.INFO, 3000);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        progressBar.setString("Preparing download…");
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
        progressBar.setIndeterminate(false);
        updateProgressBar(0, 1, "Preparing packing slips…");
        showBanner("Preparing packing slip generation…", BannerStyle.INFO, 3000);

        new SwingWorker<List<Path>, Integer>() {
            @Override
            protected List<Path> doInBackground() throws Exception {
                List<Path> generated = packingSlipGenerator.generatePackingSlips(batch, outputDir);
                int total = generated.size();
                int index = 0;
                for (Path slip : generated) {
                    index++;
                    publish((index << 16) | Math.min(total, 0xFFFF));
                    appendLog("Created slip: " + slip.getFileName());
                }
                return generated;
            }

            @Override
            protected void process(List<Integer> chunks) {
                if (chunks.isEmpty()) {
                    return;
                }
                int packed = chunks.get(chunks.size() - 1);
                int total = packed & 0xFFFF;
                int index = packed >>> 16;
                updateProgressBar(index, Math.max(total, 1), "Generating packing slips…");
            }

            @Override
            protected void done() {
                try {
                    List<Path> generated = get();
                    appendLog("Packing slips generated: " + generated.size());
                    statusLabel.setText("Packing slips at " + outputDir);
                    showBanner("Packing slips generated: " + generated.size(), BannerStyle.SUCCESS, 5000);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    appendLog("Packing slip generation interrupted.");
                    showBanner("Packing slip generation interrupted", BannerStyle.WARNING, 4000);
                } catch (ExecutionException ex) {
                    appendLog("Failed to generate packing slips: " + ex.getCause().getMessage());
                    LOGGER.warning("Packing slip generation failed: " + ex.getCause());
                    showBanner("Packing slip generation failed: " + ex.getCause().getMessage(), BannerStyle.ERROR, 6000);
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
        Map<String, ItemTypeGroup> groups = batch.groups();
        if (groups.isEmpty()) {
            resetTree();
            return;
        }

        MixMetadata mixMetadata = ShippingLayoutPlanner.computeMixMetadata(groups);
        Map<ShippingSpeed, SpeedBucket> buckets = buildBuckets(groups, mixMetadata);

        DefaultMutableTreeNode root = new DefaultMutableTreeNode(ItemTypeCategorizer.MUGS_FOLDER_NAME);
        int totalTypes = 0;

        for (ShippingSpeed speed : ShippingSpeed.values()) {
            SpeedBucket speedBucket = buckets.get(speed);
            if (speedBucket == null || speedBucket.isEmpty()) {
                continue;
            }

            DefaultMutableTreeNode speedNode = new DefaultMutableTreeNode(speed.displayName());

            for (Map.Entry<String, OunceBucket> ounceEntry : speedBucket.ounces.entrySet()) {
                String ounceKey = ounceEntry.getKey();
                OunceBucket ounceBucket = ounceEntry.getValue();

                DefaultMutableTreeNode ounceNode = new DefaultMutableTreeNode(ounceKey);

                if (!ounceBucket.mixCustomers.isEmpty()) {
                    DefaultMutableTreeNode mixNode = new DefaultMutableTreeNode("mix");
                    for (CustomerAggregate customerAgg : ounceBucket.mixCustomers.values()) {
                        mixNode.add(createCustomerNode(customerAgg.customer, customerAgg.orders));
                    }
                    ounceNode.add(mixNode);
                }

                for (ItemTypeAggregate aggregate : ounceBucket.itemTypes.values()) {
                    DefaultMutableTreeNode itemNode = new DefaultMutableTreeNode(
                        new ItemTypeNodeData(aggregate.groupKey, aggregate.itemType, aggregate.orderCount)
                    );
                    for (CustomerAggregate customerAgg : aggregate.customers.values()) {
                        itemNode.add(createCustomerNode(customerAgg.customer, customerAgg.orders));
                    }
                    ounceNode.add(itemNode);
                }

                speedNode.add(ounceNode);
            }

            int typeCount = speedBucket.typeCount();
            int mixCount = speedBucket.mixOrderCount();
            totalTypes += typeCount;

            String speedLabel = speed.displayName();
            if (typeCount > 0 || mixCount > 0) {
                speedLabel = "%s (%d types%s)".formatted(
                    speed.displayName(),
                    typeCount,
                    mixCount > 0 ? ", mix" : ""
                );
            }
            speedNode.setUserObject(speedLabel);
            root.add(speedNode);
        }

        if (root.getChildCount() == 0) {
            resetTree();
            return;
        }

        root.setUserObject("%s (%d types)".formatted(
            ItemTypeCategorizer.MUGS_FOLDER_NAME,
            totalTypes
        ));

        treeModel.setRoot(root);
        expandTree();
    }

    private void resetTree() {
        treeModel.setRoot(new DefaultMutableTreeNode("No data"));
        orderTree.setRootVisible(true);
    }

    private Map<ShippingSpeed, SpeedBucket> buildBuckets(Map<String, ItemTypeGroup> groups,
                                                         MixMetadata mixMetadata) {
        Map<ShippingSpeed, SpeedBucket> buckets = new EnumMap<>(ShippingSpeed.class);
        Set<String> mixOrderIds = mixMetadata.mixOrderIds();
        Map<String, String> mixOrderOunces = mixMetadata.mixOrderOunces();

        for (Map.Entry<String, ItemTypeGroup> entry : groups.entrySet()) {
            String groupKey = entry.getKey();
            ItemTypeGroup group = entry.getValue();
            if (group == null || group.isEmpty()) {
                continue;
            }

            String defaultOunce = Optional.ofNullable(ShippingLayoutPlanner.extractOunce(group.itemType()))
                .orElse(group.itemType());

            for (CustomerGroup customer : group.customers().values()) {
                for (CustomerOrder order : customer.orders().values()) {
                    ShippingSpeed speed = ShippingLayoutPlanner.resolveShippingSpeed(order);
                    SpeedBucket speedBucket = buckets.computeIfAbsent(speed, key -> new SpeedBucket());

                    boolean mixOrder = mixOrderIds.contains(order.orderId());
                    String ounceKey = Optional.ofNullable(ShippingLayoutPlanner.extractOunce(group.itemType()))
                        .orElse(defaultOunce);
                    String mixOunce = mixOrderOunces.get(order.orderId());
                    String targetOunce = mixOrder
                        ? (mixOunce == null || mixOunce.isBlank() ? ounceKey : mixOunce)
                        : ounceKey;

                    OunceBucket ounceBucket = speedBucket.ounces.computeIfAbsent(targetOunce, key -> new OunceBucket());
                    if (mixOrder) {
                        ounceBucket.addMixOrder(customer, order);
                    } else {
                        ounceBucket.addItemTypeOrder(groupKey, group.itemType(), customer, order);
                    }
                }
            }
        }

        return buckets;
    }

    private DefaultMutableTreeNode createCustomerNode(CustomerGroup customer, List<CustomerOrder> orders) {
        DefaultMutableTreeNode customerNode = new DefaultMutableTreeNode(
            "%s (%d orders)".formatted(customer.originalBuyerName(), orders.size())
        );
        for (CustomerOrder order : orders) {
            DefaultMutableTreeNode orderNode = new DefaultMutableTreeNode("Order " + order.orderId());
            for (CustomerOrderItem item : order.items()) {
                orderNode.add(new DefaultMutableTreeNode("Item " + item.orderItemId()));
            }
            customerNode.add(orderNode);
        }
        return customerNode;
    }

    private void expandTree() {
        for (int i = 0; i < orderTree.getRowCount(); i++) {
            orderTree.expandRow(i);
        }
        orderTree.setRootVisible(true);
    }

    private void setUiEnabled(boolean enabled) {
        loadButton.setEnabled(enabled);
        Object root = treeModel.getRoot();
        boolean hasData = root instanceof DefaultMutableTreeNode node && node.getChildCount() > 0;
        downloadAllButton.setEnabled(enabled && hasData);
        downloadSelectedButton.setEnabled(enabled && hasData);
        generatePackingSlipsButton.setEnabled(enabled && hasData);
        includeLateToggle.setEnabled(enabled);
        progressBar.setEnabled(enabled);
    }

    private void appendLog(String message) {
        if (message == null || message.isBlank()) return;
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + System.lineSeparator());
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(getParentWindow(), message, "Amazon TXT Import", JOptionPane.INFORMATION_MESSAGE);
    }

    private void configureBanner() {
        bannerPanel.setBorder(new EmptyBorder(4, 8, 4, 8));
        bannerLabel.setOpaque(false);
        bannerLabel.setFont(bannerLabel.getFont().deriveFont(bannerLabel.getFont().getStyle(), 13f));
        bannerPanel.add(bannerLabel, BorderLayout.WEST);
        showBanner("Idle", BannerStyle.INFO, 0);
    }

    private void showBanner(String message, BannerStyle style, int timeoutMs) {
        if (message == null || message.isBlank()) {
            return;
        }
        Color background;
        Color foreground;
        switch (style) {
            case SUCCESS -> {
                background = new Color(0xE8F6EA);
                foreground = new Color(0x1D6B37);
            }
            case WARNING -> {
                background = new Color(0xFFF5E6);
                foreground = new Color(0x8B6100);
            }
            case ERROR -> {
                background = new Color(0xFCEBEB);
                foreground = new Color(0x9F2F2F);
            }
            default -> {
                background = new Color(0xE6F3FF);
                foreground = new Color(0x0B3D91);
            }
        }
        bannerPanel.setBackground(background);
        bannerLabel.setForeground(foreground);
        bannerLabel.setText(message);
        if (bannerResetTimer != null && bannerResetTimer.isRunning()) {
            bannerResetTimer.stop();
        }
        if (timeoutMs > 0) {
            bannerResetTimer = new Timer(timeoutMs, e -> showBanner("Idle", BannerStyle.INFO, 0));
            bannerResetTimer.setRepeats(false);
            bannerResetTimer.start();
        }
    }

    private void updateProgressBar(int processed, int total, String detail) {
        int safeTotal = Math.max(total, 1);
        int safeProcessed = Math.max(0, processed);
        progressBar.setMaximum(safeTotal);
        progressBar.setValue(Math.min(safeProcessed, safeTotal));
        String prefix = "";
        if (safeProcessed > 0 || safeTotal > 1) {
            prefix = safeProcessed + "/" + safeTotal + " ";
        }
        progressBar.setString(prefix + detail);
    }

    private Component getParentWindow() {
        return SwingUtilities.getWindowAncestor(this);
    }

    private static final class SpeedBucket {
        private final Map<String, OunceBucket> ounces = new LinkedHashMap<>();

        boolean isEmpty() {
            return ounces.values().stream().allMatch(OunceBucket::isEmpty);
        }

        int typeCount() {
            return ounces.values().stream()
                .mapToInt(bucket -> bucket.itemTypes.size())
                .sum();
        }

        int mixOrderCount() {
            return ounces.values().stream()
                .mapToInt(OunceBucket::mixOrderCount)
                .sum();
        }
    }

    private static final class OunceBucket {
        private final Map<String, ItemTypeAggregate> itemTypes = new LinkedHashMap<>();
        private final Map<String, CustomerAggregate> mixCustomers = new LinkedHashMap<>();
        private final Set<String> seenMixOrders = new LinkedHashSet<>();

        void addMixOrder(CustomerGroup customer, CustomerOrder order) {
            if (!seenMixOrders.add(order.orderId())) {
                return;
            }
            CustomerAggregate aggregate = mixCustomers.computeIfAbsent(
                customer.originalBuyerName(),
                key -> new CustomerAggregate(customer)
            );
            aggregate.orders.add(order);
        }

        void addItemTypeOrder(String groupKey,
                              String itemType,
                              CustomerGroup customer,
                              CustomerOrder order) {
            ItemTypeAggregate aggregate = itemTypes.computeIfAbsent(
                itemType,
                key -> new ItemTypeAggregate(groupKey, itemType)
            );
            aggregate.orderCount++;
            CustomerAggregate customerAggregate = aggregate.customers.computeIfAbsent(
                customer.originalBuyerName(),
                key -> new CustomerAggregate(customer)
            );
            customerAggregate.orders.add(order);
        }

        boolean isEmpty() {
            return itemTypes.isEmpty() && mixCustomers.isEmpty();
        }

        int mixOrderCount() {
            return mixCustomers.values().stream()
                .mapToInt(aggregate -> aggregate.orders.size())
                .sum();
        }
    }

    private static final class ItemTypeAggregate {
        private final String groupKey;
        private final String itemType;
        private int orderCount;
        private final Map<String, CustomerAggregate> customers = new LinkedHashMap<>();

        private ItemTypeAggregate(String groupKey, String itemType) {
            this.groupKey = groupKey;
            this.itemType = itemType;
        }
    }

    private static final class CustomerAggregate {
        private final CustomerGroup customer;
        private final List<CustomerOrder> orders = new ArrayList<>();

        private CustomerAggregate(CustomerGroup customer) {
            this.customer = customer;
        }
    }

    private record ItemTypeNodeData(String groupKey, String itemType, int orderCount) {
        @Override
        public String toString() {
            return "%s (%d orders)".formatted(itemType, orderCount);
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
            appendLog("Starting download…");
            if (itemTypes.isPresent() && !itemTypes.get().isEmpty()) {
                return downloadService.downloadItemTypes(batch, itemTypes.get(), this);
            }
            return downloadService.downloadBatch(batch, this);
        }

        @Override
        protected void process(List<DownloadStatus> chunks) {
            for (DownloadStatus status : chunks) {
                appendLog(status.description());
                updateProgressBar(status.processed(), status.total(), status.description());
                showBanner(status.description(), BannerStyle.INFO, 3500);
            }
        }

        @Override
        protected void done() {
            try {
                Path result = get();
                appendLog("Download complete at " + result);
                statusLabel.setText("Finished. Output: " + result);
                showBanner("Download finished. Output at " + result, BannerStyle.SUCCESS, 5000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                appendLog("Download interrupted.");
                showBanner("Download interrupted", BannerStyle.WARNING, 4000);
            } catch (ExecutionException ex) {
                appendLog("Download failed: " + ex.getCause().getMessage());
                LOGGER.warning("Download failed: " + ex.getCause());
                showBanner("Download failed: " + ex.getCause().getMessage(), BannerStyle.ERROR, 6000);
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
                showBanner(summary.toString(), BannerStyle.WARNING, 6000);
            } else {
                updateProgressBar(totalCount, totalCount, "Download complete");
                showBanner("Download finished – all items processed successfully.", BannerStyle.SUCCESS, 5000);
            }
        }
    }

    private record DownloadStatus(int processed, int total, String description) {
    }

    private enum BannerStyle {
        INFO,
        SUCCESS,
        WARNING,
        ERROR
    }
}
