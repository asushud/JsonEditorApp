import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.tree.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

public class JsonEditor {

    private JFrame frame;
    private JsonNode rootJson;
    private JTree tree;
    private DefaultTreeModel treeModel;
    private JButton saveButton, searchButton, findAllButton;
    private JTextField searchField;
    private File jsonFile;
    private ObjectMapper mapper = new ObjectMapper();
    private DefaultMutableTreeNode lastFoundNode = null;
    // For Find All
    private DefaultListModel<DefaultMutableTreeNode> searchResultsModel;
    private JList<DefaultMutableTreeNode> searchResultsList;

    public JsonEditor(File file) {
        this.jsonFile = file;
        frame = new JFrame("Large JSON Editor - " + file.getName());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1200, 700);
        try {
            // Read JSON safely with UTF-8
            try (FileInputStream fis = new FileInputStream(jsonFile); InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
                rootJson = mapper.readTree(reader);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame,
                    "Failed to read file as JSON/UTF-8:\n" + ex.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        // Lazy root node
        LazyJsonTreeNode rootNode = new LazyJsonTreeNode(file.getName(), rootJson);
        rootNode.loadChildren();
        treeModel = new DefaultTreeModel(rootNode);
        tree = new JTree(treeModel);
        tree.setShowsRootHandles(true);
        tree.setEditable(true);
        tree.setCellEditor(new DefaultTreeCellEditor(tree, (DefaultTreeCellRenderer) tree.getCellRenderer()) {
            @Override
            public boolean isCellEditable(java.util.EventObject event) {
                TreePath path = tree.getSelectionPath();
                if (path == null) {
                    return false;
                }
                Object node = path.getLastPathComponent();
                return node instanceof EditableValueNode;
            }
        });
        tree.expandRow(0);
        // Lazy load children on expand
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            public void treeWillExpand(TreeExpansionEvent e) {
                LazyJsonTreeNode node = (LazyJsonTreeNode) e.getPath().getLastPathComponent();
                node.loadChildren();
                treeModel.nodeStructureChanged(node);
            }

            public void treeWillCollapse(TreeExpansionEvent e) {
            }
        });
        JScrollPane treeScrollPane = new JScrollPane(tree);
        // Bottom panel with search + save
        JPanel bottomPanel = new JPanel(new FlowLayout());
        searchField = new JTextField(30);
        searchButton = new JButton("Find First");
        findAllButton = new JButton("Find All");
        saveButton = new JButton("Save");
        bottomPanel.add(new JLabel("Search:"));
        bottomPanel.add(searchField);
        bottomPanel.add(searchButton);
        bottomPanel.add(findAllButton);
        bottomPanel.add(saveButton);
        // Right panel for search results
        searchResultsModel = new DefaultListModel<>();
        searchResultsList = new JList<>(searchResultsModel);
        searchResultsList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane resultsScroll = new JScrollPane(searchResultsList);
        resultsScroll.setPreferredSize(new Dimension(300, 0));
        frame.add(treeScrollPane, BorderLayout.CENTER);
        frame.add(bottomPanel, BorderLayout.SOUTH);
        frame.add(resultsScroll, BorderLayout.EAST);
        // Action: Find Next
        searchButton.addActionListener(e -> searchJson());
        // Action: Find All
        findAllButton.addActionListener(e -> findAllMatches());
        // Clicking a search result scrolls tree
        searchResultsList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                DefaultMutableTreeNode node = searchResultsList.getSelectedValue();
                if (node != null) {
                    TreePath path = new TreePath(node.getPath());
                    tree.scrollPathToVisible(path);
                    tree.setSelectionPath(path);
                }
            }
        });
        // Save action
        saveButton.addActionListener(e -> {
            try {
                saveJson();
                JOptionPane.showMessageDialog(frame, "File saved successfully!");
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(frame, "Error saving file: " + ex.getMessage());
            }
        });
        frame.setVisible(true);
    }

    /**
     * Lazy-loading node
     */
    static class LazyJsonTreeNode extends DefaultMutableTreeNode {

        private boolean loaded = false;
        private final JsonNode jsonNode;

        public LazyJsonTreeNode(String name, JsonNode jsonNode) {
            super(name);
            this.jsonNode = jsonNode;
        }

        @Override
        public boolean isLeaf() {
            return jsonNode == null || jsonNode.isValueNode();
        }

        @Override
        public int getChildCount() {
            if (!loaded) {
                loadChildren();
            }
            return super.getChildCount();
        }

        public void loadChildren() {
            if (loaded || jsonNode == null) {
                return;
            }
            loaded = true;
            removeAllChildren();
            if (jsonNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = jsonNode.fields();
                boolean hasFields = false;
                while (fields.hasNext()) {
                    hasFields = true;
                    Map.Entry<String, JsonNode> entry = fields.next();
                    JsonNode value = entry.getValue();
                    if (value == null || value.isValueNode() || value.isNull()) {
                        add(new EditableValueNode(entry.getKey(), jsonNode, false, -1));
                    } else {
                        add(new LazyJsonTreeNode(entry.getKey(), value));
                    }
                }
                if (!hasFields) {
                    add(new DefaultMutableTreeNode("[empty object]"));
                }
            } else if (jsonNode.isArray()) {
                if (jsonNode.size() == 0) {
                    add(new DefaultMutableTreeNode("[empty array]"));
                }else {
                    for (int i = 0; i < jsonNode.size(); i++) {
                        JsonNode value = jsonNode.get(i);
                        if (value == null || value.isValueNode() || value.isNull()) {
                            add(new EditableValueNode("", jsonNode, true, i));
                        }else {
                            add(new LazyJsonTreeNode("[" + i + "]", value));
                        }
                    }
                }
            } else if (jsonNode.isValueNode()) {
                add(new DefaultMutableTreeNode(EditableValueNode.getNodeText(jsonNode)));
            }
        }
    }

    /**
     * Step-by-step Find Next with wrap-around
     */
    private void searchJson() {
        String query = searchField.getText().toLowerCase();
        if (query.isEmpty()) {
            return;
        }
        javax.swing.tree.DefaultMutableTreeNode root = (javax.swing.tree.DefaultMutableTreeNode) treeModel.getRoot();
        Queue<javax.swing.tree.DefaultMutableTreeNode> queue = new LinkedList<>();
        queue.add(root);
        boolean foundNext = false;
        boolean startSearch = (lastFoundNode == null);
        while (!queue.isEmpty()) {
            javax.swing.tree.DefaultMutableTreeNode node = queue.poll();
            if (node instanceof LazyJsonTreeNode) {
                ((LazyJsonTreeNode) node).loadChildren();
                treeModel.nodeStructureChanged(node);
            }
            if (!startSearch) {
                if (node == lastFoundNode) {
                    startSearch = true;
                }
                continue;
            }
            if (node.toString().toLowerCase().contains(query)) {
                TreePath path = new TreePath(node.getPath());
                tree.scrollPathToVisible(path);
                tree.setSelectionPath(path);
                lastFoundNode = node;
                foundNext = true;
                break;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                queue.add((javax.swing.tree.DefaultMutableTreeNode) node.getChildAt(i));
            }
        }
        if (!foundNext) {
            if (lastFoundNode != null) {
                lastFoundNode = null;
                searchJson(); // wrap-around
            } else {
                JOptionPane.showMessageDialog(frame, "No matches found.");
            }
        }
    }

    /**
     * Responsive Find All using SwingWorker
     */
    private void findAllMatches() {
        String query = searchField.getText().toLowerCase();
        if (query.isEmpty()) {
            return;
        }
        searchResultsModel.clear();
        SwingWorker<Void, javax.swing.tree.DefaultMutableTreeNode> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                javax.swing.tree.DefaultMutableTreeNode root = (javax.swing.tree.DefaultMutableTreeNode) treeModel.getRoot();
                Queue<javax.swing.tree.DefaultMutableTreeNode> queue = new LinkedList<>();
                queue.add(root);
                while (!queue.isEmpty() && !isCancelled()) {
                    javax.swing.tree.DefaultMutableTreeNode node = queue.poll();
                    if (node instanceof LazyJsonTreeNode) {
                        ((LazyJsonTreeNode) node).loadChildren();
                        SwingUtilities.invokeLater(() -> treeModel.nodeStructureChanged(node));
                    }
                    if (node.toString().toLowerCase().contains(query)) {
                        publish(node);
                    }
                    for (int i = 0; i < node.getChildCount(); i++) {
                        queue.add((javax.swing.tree.DefaultMutableTreeNode) node.getChildAt(i));
                    }
                }
                return null;
            }

            @Override
            protected void process(List<javax.swing.tree.DefaultMutableTreeNode> chunks) {
                for (javax.swing.tree.DefaultMutableTreeNode node : chunks) {
                    searchResultsModel.addElement(node);
                }
            }

            @Override
            protected void done() {
                if (searchResultsModel.isEmpty()) {
                    JOptionPane.showMessageDialog(frame, "No matches found.");
                } else {
                    searchResultsList.setSelectedIndex(0);
                }
            }
        };
        worker.execute();
    }

    /**
     * Save JSON back to file
     */
    private void saveJson() throws IOException {
        try (FileOutputStream fos = new FileOutputStream(jsonFile); OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(writer, rootJson);
        }
    }

    /**
     * Main entry
     */
    public static void main(String[] args) {
        File file;
        if (args.length == 0) {
            JFileChooser chooser = new JFileChooser(".");
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON/TXT Files", "json", "txt"));
            int result = chooser.showOpenDialog(null);
            if (result == JFileChooser.APPROVE_OPTION) {
                file = chooser.getSelectedFile();
            }else {
                return;
            }
        } else {
            file = new File(args[0]);
        }
        if (!file.exists()) {
            JOptionPane.showMessageDialog(null, "File not found: " + file.getAbsolutePath());
            return;
        }
        SwingUtilities.invokeLater(() -> new JsonEditor(file));
    }

    /**
     * Editable value node
     */
    static class EditableValueNode extends DefaultMutableTreeNode {

        private final String key;
        private final JsonNode parentNode;
        private final boolean isArrayElement;
        private final int arrayIndex;

        public EditableValueNode(String key, JsonNode parentNode, boolean isArrayElement, int arrayIndex) {
            super(isArrayElement
                    ? ("[" + arrayIndex + "]: " + getNodeText(parentNode.get(arrayIndex)))
                    : (key + ": " + getNodeText(parentNode instanceof ObjectNode ? ((ObjectNode) parentNode).get(key) : null)));
            this.key = key;
            this.parentNode = parentNode;
            this.isArrayElement = isArrayElement;
            this.arrayIndex = arrayIndex;
        }

        @Override
        public boolean isLeaf() {
            return true;
        }

        @Override
        public void setUserObject(Object userObject) {
            String str = userObject.toString();
            String newValue = str.contains(":") ? str.substring(str.indexOf(":") + 1).trim() : str.trim();
            JsonNode valueNode;
            if (newValue.matches("-?\\d+")) {
                valueNode = new IntNode(Integer.parseInt(newValue));
            }else if (newValue.equalsIgnoreCase("true") || newValue.equalsIgnoreCase("false")) {
                valueNode = BooleanNode.valueOf(Boolean.parseBoolean(newValue));
            }else if (newValue.equalsIgnoreCase("null")) {
                valueNode = NullNode.getInstance();
            }else {
                valueNode = new TextNode(newValue);
            }
            if (isArrayElement && parentNode instanceof ArrayNode) {
                ((ArrayNode) parentNode).set(arrayIndex, valueNode);
            }else if (parentNode instanceof ObjectNode) {
                ((ObjectNode) parentNode).set(key, valueNode);
            }
            super.setUserObject(isArrayElement ? "[" + arrayIndex + "]: " + newValue : key + ": " + newValue);
        }

        private static String getNodeText(JsonNode node) {
            if (node == null || node.isNull()) {
                return "null";
            }
            if (node.isTextual()) {
                return node.asText();
            }
            if (node.isNumber()) {
                return node.numberValue().toString();
            }
            if (node.isBoolean()) {
                return Boolean.toString(node.asBoolean());
            }
            return node.toString();
        }
    }
}
